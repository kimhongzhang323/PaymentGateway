# Phase 3b — CI Quality Gates: Design

**Date:** 2026-05-27
**Status:** Approved (scope + tooling)
**Type:** Phase implementation spec (Phase 3 production-readiness, slice b). Follows the roadmap `docs/superpowers/specs/2026-05-25-payment-gateway-roadmap-design.md` §7b.
**Branch:** `feat/phase3b-cicd`

---

## 1. Goal & Framing

Turn the minimal CI (`build-and-test` on H2 + Qodana + an unpushed docker build) into a **CI quality-gate pipeline** for a sandbox-grade payment gateway: every PR to `main` must pass tests, a coverage floor, dependency-CVE scanning, secret scanning, and migration validation, with static analysis and container image scanning running advisory.

**Explicitly scoped OUT of 3b** (deferred):
- Any environment deploy — staging, smoke tests, gated prod promotion. There is no live deploy target for a sandbox-grade, backend-only project; revisit when real infra exists.
- Image **publish** to a registry (GHCR). Build + scan only.
- **Testcontainers** migration of the existing H2 integration suite → **Phase 3c (Full QA Test Plan)**. 3b wires the CI *around* the existing green suite and lays the Failsafe seam for future `*IT` tests.
- Observability (metrics/tracing/dashboards) → **Phase 3d**.

## 2. Confirmed Decisions

| Decision | Choice |
|---|---|
| Pipeline depth | CI quality gates only — stop before any environment deploy |
| Gate strictness | **Hard-fail:** unit + integration tests, coverage floor (payment-core), HIGH/CRITICAL dependency CVEs, leaked secrets, migration validation. **Advisory:** Qodana/SAST, container image scan |
| Integration tests | Run the existing H2-based suite in CI as-is; add JaCoCo + Failsafe wiring; defer Testcontainers to 3c |
| Tooling | Trivy-centric + Java-native (Approach A) |
| Coverage gate | Ratchet-from-baseline (see §5) |

## 3. Tooling (Approach A)

| Concern | Tool | Mode | Gate |
|---|---|---|---|
| Coverage | JaCoCo Maven plugin | `report` + `check` rule, ≥80% line scoped to `payment-core` | hard-fail |
| Integration-test seam | Maven Failsafe plugin | runs `*IT`; bound to `verify` | hard-fail (none migrated yet) |
| Dependency CVEs | Trivy | `trivy fs` over repo/Maven deps | hard-fail HIGH/CRITICAL |
| Container image | Trivy | `trivy image` on the built image, SARIF upload | advisory |
| Secrets | Gitleaks | full-history + diff scan | hard-fail on any finding |
| SAST | Qodana | unchanged | advisory (`continue-on-failure`) |
| Migration validation | Flyway Maven plugin | `flyway:migrate` against CI Postgres (env-overridable URL) | hard-fail |

Rejected alternatives: **OWASP Dependency-Check** (slow/flaky NVD download, now needs an API key, balloons build time); **GitHub-native** (`dependency-review-action` only scans PR diffs, so it would miss the existing Bucket4j/Resilience4j CVEs flagged in the 3a audit; CodeQL would displace Qodana).

## 4. Pipeline Structure

Single restructured `.github/workflows/ci-cd.yml`, parallel jobs on `pull_request` + `push` to `main`. Jobs are independent so failures are isolated; `fail-fast` is enforced via branch-protection required checks.

| Job | Does | Gate |
|---|---|---|
| `build-test` | `./mvnw verify` (Surefire unit + Failsafe `*IT`), Postgres + Redis services up, JaCoCo report + `check`; upload jar + jacoco report | **hard-fail**: tests, coverage |
| `migration-validation` | `flyway:migrate` against the CI Postgres service (real prod-flavored schema, not H2) | **hard-fail** |
| `dependency-scan` | `trivy fs` over repo/Maven deps | **hard-fail** HIGH/CRITICAL |
| `secret-scan` | Gitleaks full-history + diff | **hard-fail** on any finding |
| `static-analysis` | Qodana (unchanged) | advisory |
| `image-build-scan` | `needs: build-test`; docker build → `trivy image` → SARIF upload | advisory (no block, no push) |

**Branch protection:** the four hard-fail jobs (`build-test`, `migration-validation`, `dependency-scan`, `secret-scan`) must be added as **required status checks** on `main` for the gates to actually block merges. This is a GitHub repo setting, not in-repo config — documented as a manual step in the implementation plan and the decision-log entry.

## 5. Coverage Gate: Ratchet-from-Baseline

JaCoCo has never run; today's `payment-core` line coverage is unknown. A hard 80% gate could block all of 3b on a test-writing effort that belongs in 3c.

**Handling:** the first implementation step wires JaCoCo and *measures* the baseline. Then:
- baseline ≥ 80% → set the `check` rule at **80%**.
- baseline < 80% → set the `check` rule at the **measured baseline (rounded down to a whole percent)** and open a 3c follow-up to ratchet to 80%.

Either way the gate prevents **regression** from day one — the actual purpose of a CI coverage gate. The chosen threshold and measured baseline are recorded in the decision-log entry.

## 6. CVE Escape Hatch

`trivy fs` HIGH/CRITICAL hard-fail directly closes 3a-audit item **M4** (verify Bucket4j 8.14.0 / Resilience4j 2.3.0 CVE status). To keep the gate strict without becoming a permanent blocker on an unfixable transitive CVE, add a checked-in `.trivyignore` where each entry carries a justification comment and an expiry date. An empty/comment-only `.trivyignore` is the desired end state; entries are debt, not config.

## 7. Verification

A GitHub Actions workflow cannot be fully proven locally. The plan verifies in layers:
1. **Locally via `./mvnw`:** JaCoCo report + check, Failsafe binding, and `flyway:migrate` (against a local/`docker compose` Postgres) all run green.
2. **Locally:** `trivy fs`, `trivy image`, and `gitleaks` run against the repo and produce the expected pass/fail.
3. **End-to-end:** open the 3b PR and confirm every job runs and the hard-fail gates report as required checks. The gates firing correctly on a real PR run is the acceptance criterion.

## 8. Exit Criteria

- PR to `main` runs all six jobs; the four hard-fail gates block merge on failure and are configured as required checks.
- `payment-core` coverage gate enforced at 80% or the recorded baseline; regression is blocked.
- No HIGH/CRITICAL dependency CVE (or each is justified+dated in `.trivyignore`); 3a-audit M4 closed.
- No secret detectable by Gitleaks in history or diff.
- Flyway migrations apply cleanly to a real Postgres in CI.
- Decision-log entry records tooling, the coverage threshold + baseline, the branch-protection manual step, and any `.trivyignore` debt.

## 9. Out-of-Scope Follow-ups (tracked, not built here)

- Testcontainers integration profile + migration of infra-dependent tests → **3c**.
- Ratchet `payment-core` coverage to 80% if baseline started lower → **3c**.
- Image publish to GHCR + environment deploy + smoke tests → later (needs real infra).
- Observability stack → **3d**.
