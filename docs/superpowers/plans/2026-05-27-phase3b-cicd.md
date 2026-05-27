# Phase 3b — CI Quality Gates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn KimPay's minimal CI into a CI quality-gate pipeline — tests, a coverage floor, dependency-CVE + secret scanning, and migration validation hard-fail PRs to `main`; static analysis and image scanning run advisory.

**Architecture:** Maven-side wiring (JaCoCo coverage check on `payment-core`, Failsafe seam for `*IT`, Flyway plugin for migration validation) plus a restructured `.github/workflows/ci-cd.yml` with parallel jobs. Trivy covers both dependency (`fs`, hard-fail) and image (advisory) scanning; Gitleaks covers secrets. No environment deploy, no image publish (deferred).

**Tech Stack:** Maven (Spring Boot 3.5.7 parent, Java 17), JaCoCo 0.8.13, Maven Failsafe, Flyway Maven plugin, GitHub Actions, Trivy, Gitleaks, Qodana.

**Spec:** `docs/superpowers/specs/2026-05-27-phase3b-cicd-design.md`

---

## File Structure

| File | Responsibility | Action |
|---|---|---|
| `payment-core/pom.xml` | JaCoCo agent + report + coverage `check` rule (scoped to this module) | Modify |
| `pom.xml` (root) | Maven Failsafe plugin in `<build><plugins>` so every module runs `*IT` at `verify` | Modify |
| `payment-api/pom.xml` | Flyway Maven plugin (env-overridable URL) for migration validation | Modify |
| `.github/workflows/ci-cd.yml` | Restructured pipeline: 6 parallel jobs, 4 hard-fail gates | Modify |
| `.trivyignore` | Documented + dated escape hatch for unfixable transitive CVEs (starts comment-only) | Create |
| `.claude/docs/decision-log.md` | Phase 3b entry: tooling, coverage threshold+baseline, branch-protection manual step | Modify |

**Note on TDD:** Maven plugin tasks are verified by running the goal locally and observing pass/fail (the "test" is the build outcome). The GitHub workflow itself cannot be fully proven locally; its acceptance check is the gates firing on a real PR run (Task 7).

---

### Task 1: JaCoCo coverage report + measure baseline

**Files:**
- Modify: `payment-core/pom.xml` (add to existing `<build><plugins>`)

- [ ] **Step 1: Add the JaCoCo plugin (report only for now — no check yet)**

In `payment-core/pom.xml`, inside `<build><plugins>` (alongside the existing spring-boot-maven-plugin), add:

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.13</version>
    <executions>
        <execution>
            <id>prepare-agent</id>
            <goals>
                <goal>prepare-agent</goal>
            </goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>test</phase>
            <goals>
                <goal>report</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

- [ ] **Step 2: Run tests + report and read the baseline**

Run: `./mvnw -pl payment-core -am test`
Then open `payment-core/target/site/jacoco/index.html` and read the **Line** coverage percentage for the bundle (the "Total" row).

Expected: build PASSES; a JaCoCo report exists. Record the line-coverage % — call it `BASELINE`.

- [ ] **Step 3: Decide the threshold**

- If `BASELINE >= 80%` → `THRESHOLD = 0.80`.
- If `BASELINE < 80%` → `THRESHOLD = floor(BASELINE) / 100` (e.g. baseline 71.4% → `0.71`). A 3c follow-up will ratchet to 0.80.

Write the chosen `THRESHOLD` and the measured `BASELINE` down — they go in the decision-log entry (Task 6).

- [ ] **Step 4: Add the coverage `check` execution**

Append a third execution to the JaCoCo plugin from Step 1 (replace `THRESHOLD` with the value from Step 3, e.g. `0.80`):

```xml
        <execution>
            <id>check</id>
            <phase>verify</phase>
            <goals>
                <goal>check</goal>
            </goals>
            <configuration>
                <rules>
                    <rule>
                        <element>BUNDLE</element>
                        <limits>
                            <limit>
                                <counter>LINE</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>THRESHOLD</minimum>
                            </limit>
                        </limits>
                    </rule>
                </rules>
            </configuration>
        </execution>
```

- [ ] **Step 5: Verify the gate passes at the chosen threshold**

Run: `./mvnw -pl payment-core -am verify`
Expected: BUILD SUCCESS. JaCoCo `check` reports the rule satisfied (coverage ≥ THRESHOLD).

- [ ] **Step 6: Verify the gate actually fails when violated (prove it bites)**

Temporarily edit the `<minimum>` to `0.99`, run `./mvnw -pl payment-core verify`.
Expected: BUILD FAILURE — `Rule violated for bundle ... lines covered ratio is X, but expected minimum 0.99`.
Then restore `<minimum>` to `THRESHOLD`.

- [ ] **Step 7: Commit**

```bash
git add payment-core/pom.xml
git commit -m "build(cicd): JaCoCo coverage report + check gate on payment-core"
```

---

### Task 2: Maven Failsafe seam for integration tests

**Files:**
- Modify: `pom.xml` (root, `<build><plugins>`)

- [ ] **Step 1: Add the Failsafe plugin to the root build**

In the root `pom.xml`, inside `<build><plugins>` (after the maven-compiler-plugin block), add (version is managed by the Spring Boot parent — omit it):

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-failsafe-plugin</artifactId>
    <executions>
        <execution>
            <goals>
                <goal>integration-test</goal>
                <goal>verify</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

- [ ] **Step 2: Verify the full build still passes with Failsafe bound**

Run: `./mvnw clean verify`
Expected: BUILD SUCCESS. Surefire runs the existing `*Test` classes; Failsafe runs `*IT` (none exist yet, so it reports "No tests to run" — this is the intended empty seam, not an error).

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build(cicd): bind Maven Failsafe for *IT integration tests (seam for 3c)"
```

---

### Task 3: Flyway Maven plugin for migration validation

**Files:**
- Modify: `payment-api/pom.xml` (`<properties>` + `<build><plugins>`)

- [ ] **Step 1: Add env-overridable Flyway connection properties**

In `payment-api/pom.xml`, add a `<properties>` block (the module has none today) just after the `<description>` line:

```xml
    <properties>
        <flyway.url>jdbc:postgresql://localhost:5432/payment_gateway</flyway.url>
        <flyway.user>postgres</flyway.user>
        <flyway.password>postgres</flyway.password>
    </properties>
```

- [ ] **Step 2: Add the Flyway Maven plugin**

In `payment-api/pom.xml`, inside `<build><plugins>` (after the spring-boot-maven-plugin), add (version managed by the Spring Boot parent):

```xml
<plugin>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-maven-plugin</artifactId>
    <configuration>
        <url>${flyway.url}</url>
        <user>${flyway.user}</user>
        <password>${flyway.password}</password>
        <locations>filesystem:src/main/resources/db/migration</locations>
        <cleanDisabled>true</cleanDisabled>
    </configuration>
    <dependencies>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
            <version>${flyway.version}</version>
        </dependency>
    </dependencies>
</plugin>
```

> Note: the plugin *dependency* needs an explicit `<version>` — Maven does not apply the parent's `dependencyManagement` to plugin-level dependencies. `${flyway.version}` is parent-managed (resolves to 11.7.2), so it stays in sync with `flyway-core`. The plugin itself still inherits its version from the Spring Boot parent.

- [ ] **Step 3: Verify migrations apply against a real Postgres locally**

Start Postgres (the repo's `docker compose up` brings one up, or any local Postgres matching the properties). Then run:

`./mvnw -pl payment-api flyway:migrate -Dflyway.url=jdbc:postgresql://localhost:5432/payment_gateway -Dflyway.user=postgres -Dflyway.password=postgres`

Expected: `Successfully applied N migrations` (V1–V7) or `Schema ... is up to date` on a re-run. No SQL errors. This proves the migrations are valid against PostgreSQL (tests use H2, so this catches Postgres-only syntax drift).

- [ ] **Step 4: Commit**

```bash
git add payment-api/pom.xml
git commit -m "build(cicd): Flyway Maven plugin for CI migration validation"
```

---

### Task 4: `.trivyignore` escape hatch

**Files:**
- Create: `.trivyignore` (repo root)

- [ ] **Step 1: Create a comment-only `.trivyignore`**

```
# Trivy ignore file — documented, dated exceptions only.
# Each entry MUST carry: CVE id, why it is unfixable here, and an expiry date.
# An empty (comment-only) file is the desired end state — entries are debt.
#
# Format:
#   CVE-YYYY-NNNNN  # reason; revisit by YYYY-MM-DD
#
# (no active exceptions)
```

- [ ] **Step 2: Commit**

```bash
git add .trivyignore
git commit -m "build(cicd): add documented .trivyignore escape hatch (empty)"
```

---

### Task 5: Restructure the CI workflow into quality-gate jobs

**Files:**
- Modify: `.github/workflows/ci-cd.yml` (full rewrite of the jobs)

- [ ] **Step 1: Replace the workflow with the gated pipeline**

Replace the entire contents of `.github/workflows/ci-cd.yml` with:

```yaml
name: Payment Gateway CI

on:
  push:
    branches: [ "main" ]
  pull_request:
    branches: [ "main" ]

jobs:
  build-test:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:15
        env:
          POSTGRES_USER: postgres
          POSTGRES_PASSWORD: postgres
          POSTGRES_DB: payment_gateway
        ports:
          - 5432:5432
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
      redis:
        image: redis:7-alpine
        ports:
          - 6379:6379
        options: >-
          --health-cmd "redis-cli ping"
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: maven
      - name: Build, test, coverage gate
        run: ./mvnw clean verify -B
      - name: Upload coverage report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: jacoco-report
          path: payment-core/target/site/jacoco/
      - name: Upload jar
        if: github.event_name == 'push' && github.ref == 'refs/heads/main'
        uses: actions/upload-artifact@v4
        with:
          name: payment-api-jar
          path: payment-api/target/payment-api-*.jar

  migration-validation:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:15
        env:
          POSTGRES_USER: postgres
          POSTGRES_PASSWORD: postgres
          POSTGRES_DB: payment_gateway
        ports:
          - 5432:5432
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: maven
      - name: Validate migrations against PostgreSQL
        run: >-
          ./mvnw -pl payment-api flyway:migrate -B
          -Dflyway.url=jdbc:postgresql://localhost:5432/payment_gateway
          -Dflyway.user=postgres
          -Dflyway.password=postgres

  dependency-scan:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Trivy dependency scan (HIGH/CRITICAL fail)
        uses: aquasecurity/trivy-action@0.28.0
        with:
          scan-type: fs
          scan-ref: .
          severity: HIGH,CRITICAL
          exit-code: '1'
          ignore-unfixed: false
          trivyignores: .trivyignore

  secret-scan:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - name: Gitleaks secret scan
        uses: gitleaks/gitleaks-action@v2
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}

  static-analysis:
    runs-on: ubuntu-latest
    permissions:
      contents: write
      pull-requests: write
      security-events: write
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - name: Qodana Scan (advisory)
        uses: JetBrains/qodana-action@v2025.2
        continue-on-error: true
        env:
          Q_TEST_CONTINUE_ON_FAILURE: true

  image-build-scan:
    needs: build-test
    runs-on: ubuntu-latest
    permissions:
      security-events: write
    steps:
      - uses: actions/checkout@v4
      - name: Download jar
        if: github.event_name == 'push' && github.ref == 'refs/heads/main'
        uses: actions/download-artifact@v4
        with:
          name: payment-api-jar
          path: payment-api/target/
      - name: Build jar (PR runs without the uploaded artifact)
        if: github.event_name != 'push' || github.ref != 'refs/heads/main'
        run: ./mvnw -pl payment-api -am package -DskipTests -B
      - name: Build Docker image
        run: docker build -t kimpay/payment-api:ci -f payment-api/Dockerfile .
      - name: Trivy image scan (advisory)
        uses: aquasecurity/trivy-action@0.28.0
        with:
          scan-type: image
          image-ref: kimpay/payment-api:ci
          severity: HIGH,CRITICAL
          exit-code: '0'
          format: sarif
          output: trivy-image.sarif
      - name: Upload image scan SARIF
        if: always()
        uses: github/codeql-action/upload-sarif@v3
        with:
          sarif_file: trivy-image.sarif
```

- [ ] **Step 2: Lint the YAML locally**

Run: `./mvnw -v` is unrelated; instead validate YAML syntax with any available linter, e.g.
`python -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci-cd.yml')); print('YAML OK')"`
Expected: `YAML OK` (no parse error).

- [ ] **Step 3: Verify the hard-fail Maven path locally mirrors `build-test`**

Run: `./mvnw clean verify -B`
Expected: BUILD SUCCESS — the same command the `build-test` job runs (tests + JaCoCo check).

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci-cd.yml
git commit -m "ci(cicd): restructure into gated pipeline (coverage, deps, secrets, migrations)"
```

---

### Task 6: Decision-log entry + branch-protection documentation

**Files:**
- Modify: `.claude/docs/decision-log.md` (prepend a new dated entry under the existing newest)

- [ ] **Step 1: Add the decision-log entry**

Insert after the most recent entry's separator (replace `BASELINE`/`THRESHOLD` with the values recorded in Task 1 Step 3):

```markdown
## 2026-05-27 — Phase 3b: CI quality gates

**Context:** Production-readiness needs CI that blocks unsafe merges. Prior CI was a single H2 build + advisory Qodana + an unpushed docker build. Sandbox-grade, no live deploy target, so 3b stops at CI gates (deploy/staging/prod promotion and image publish deferred).

**Decision:** Restructured `.github/workflows/ci-cd.yml` into parallel jobs. **Hard-fail gates:** `build-test` (`mvn verify` — Surefire + Failsafe + JaCoCo coverage `check` on payment-core), `migration-validation` (Flyway migrate against a real Postgres service — catches PostgreSQL-only drift that H2 tests miss), `dependency-scan` (`trivy fs`, HIGH/CRITICAL), `secret-scan` (Gitleaks, full history). **Advisory:** Qodana (unchanged) and `image-build-scan` (`trivy image`, SARIF upload, no block, no push). Tooling = Trivy-centric + Java-native (Approach A): rejected OWASP Dependency-Check (slow/flaky NVD, needs API key) and GitHub-native dependency-review (PR-diff only, would miss existing Bucket4j/Resilience4j CVEs). `.trivyignore` is the documented+dated escape hatch (starts empty). Coverage gate set at **THRESHOLD** (measured payment-core baseline **BASELINE**). Testcontainers migration of the H2 suite deferred to 3c; the Failsafe `*IT` seam is wired now.

**Manual step (not in-repo config):** the four hard-fail jobs (`build-test`, `migration-validation`, `dependency-scan`, `secret-scan`) must be added as **required status checks** on `main` in GitHub branch-protection settings for the gates to block merges. Until then they run but do not enforce.

**Consequences:** Closes 3a-audit **M4** (Bucket4j 8.14.0 / Resilience4j 2.3.0 CVE status now scanned). 3c follow-ups: ratchet coverage to 80% if baseline started lower; Testcontainers integration profile. Image publish to GHCR + environment deploy remain deferred until real infra exists.
```

- [ ] **Step 2: Commit**

```bash
git add .claude/docs/decision-log.md
git commit -m "docs(cicd): record Phase 3b CI quality-gates decision"
```

---

### Task 7: End-to-end PR verification (acceptance)

**Files:** none (operational)

- [ ] **Step 1: Push the branch and open a PR to `main`**

```bash
git push -u origin feat/phase3b-cicd
```
Open a PR via `gh pr create` (or the GitHub UI).

- [ ] **Step 2: Confirm all six jobs run**

On the PR's Checks tab, verify these appear and complete: `build-test`, `migration-validation`, `dependency-scan`, `secret-scan`, `static-analysis`, `image-build-scan`.
Expected: the four hard-fail jobs are green; advisory jobs may be green or report findings without failing the PR.

- [ ] **Step 3: Configure branch protection (manual)**

In GitHub repo Settings → Branches → branch-protection rule for `main`, add the four hard-fail jobs as **required status checks**. (Cannot be done from the repo; this is the documented manual step from Task 6.)

- [ ] **Step 4: Verify a gate blocks (optional sanity check)**

Push a throwaway commit that plants a fake secret (e.g. `aws_secret_access_key = AKIA...` in a scratch file) to confirm `secret-scan` fails, then revert it. Do NOT merge the throwaway commit.
Expected: `secret-scan` job fails on the planted secret; passes again after revert.

---

## Self-Review Notes

- **Spec coverage:** §3 tooling → Tasks 1,3,4,5. §4 pipeline structure → Task 5. §5 coverage ratchet → Task 1 Steps 2–4. §6 CVE escape hatch → Task 4 + Task 5 `trivyignores`. §7 verification → Task 7 + per-task local verify steps. §8 exit criteria → Tasks 5–7. Branch-protection manual step → Tasks 6,7.
- **Failsafe note:** "No tests to run" is expected (no `*IT` exist yet) — this is the intended seam, documented in Task 2 Step 2.
- **Coverage threshold** is a single value chosen in Task 1 and reused verbatim in Task 6 — keep them identical.
