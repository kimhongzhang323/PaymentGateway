---
description: Drive a minimal, well-tested hotfix for an urgent production issue
argument-hint: "<short description of the urgent issue>"
---

Produce a minimal hotfix for: $ARGUMENTS

Constraints — this is urgent and production-facing, so be disciplined, not reckless:
1. Create a branch `hotfix/<slug>` off `main` (never work on `main` directly).
2. Write a failing regression test that captures the bug first.
3. Make the **smallest** change that fixes the root cause. No refactoring, no scope creep.
4. Run the regression test and the full `./mvnw test` suite — both must be green.
5. If the fix needs a schema change, add a new additive Flyway migration (never edit an applied one; never a destructive down-migration on prod data).
6. Verify nothing sensitive is logged and the error contract is unchanged.
7. Commit with `fix:` naming the root cause. Summarize the change, the test, and the rollback path (redeploy previous image tag).

If the root cause is unclear, run `/triage` first — do not ship a guess.
