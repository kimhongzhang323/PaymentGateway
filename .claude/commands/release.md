---
description: Prepare a KimPay release - verify gates, version, changelog, and tag
argument-hint: "<version, e.g. 0.1.0>"
---

Prepare release `v$ARGUMENTS`.

1. **Verify gates** (see `.claude/skills/deploy`): `./mvnw clean verify` green; CI quality gates pass (coverage, no high/critical CVEs, image scan, Flyway validation).
2. **Confirm migrations** since the last release are additive and reviewed.
3. **Bump version** to `$ARGUMENTS` in the parent `pom.xml` and module poms (keep them in sync).
4. **Changelog:** summarize changes since the last tag grouped by feat / fix / security / docs. Call out any breaking changes and required new environment variables.
5. **Update docs** if the public API, auth scheme, or config changed (`ARCHITECTURE.md`, `docs/security/`, OpenAPI).
6. **Tag:** commit the version bump (`chore(release): v$ARGUMENTS`) and create an annotated tag `v$ARGUMENTS`.
7. Output the deploy checklist and rollback plan.

Do not push or deploy unless explicitly asked.
