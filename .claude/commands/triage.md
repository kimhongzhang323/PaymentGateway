---
description: Triage a bug report or failing build - reproduce, find root cause, propose a fix plan
argument-hint: "<description of the issue or failing test>"
---

Triage this issue: $ARGUMENTS

1. **Gather evidence.** Read the relevant code and, for test/build failures, the actual stack trace in `target/surefire-reports/`. Reproduce with the narrowest command (`./mvnw -pl <module> -am test -Dtest=<Class>`).
2. **Locate the root cause** using the `.claude/skills/fix-bug` approach. Trace to origin; do not guess. Note any KimPay-specific pitfalls (conditional `@Component` beans, H2-vs-Postgres, lock handling, `BigDecimal` scale).
3. **Assess impact & severity:** does it affect money correctness, security, or availability? Is data at risk?
4. **Propose a fix plan:** one-sentence root cause, the minimal change, the regression test to add, and any migration/rollback considerations.

Stop after triage. Do not implement unless explicitly asked — output the diagnosis and plan.
