---
name: fix-bug
description: Use when investigating a bug, test failure, or unexpected behavior in KimPay - enforces root-cause analysis and a regression test before any fix.
---

# Fix Bug

Systematic debugging for the payment gateway. No fix without a root cause and a regression test.

## Process
1. **Reproduce.** Write a failing test that reproduces the bug. If you can't reproduce it, you don't understand it yet.
2. **Find the root cause.** Read the actual stack trace (surefire reports under `target/surefire-reports/`). Trace the failure to its origin. Do not patch symptoms.
   - Common KimPay pitfalls: `@ConditionalOnMissingBean` on a scanned `@Component` (unreliable — use a `@Configuration @Bean` fallback); missing bean when an infra profile is disabled; H2-vs-Postgres SQL differences; lock not released in `finally`; `BigDecimal` scale/equality mistakes.
3. **Confirm the cause.** State the cause in one sentence and verify it explains *all* observed symptoms.
4. **Fix minimally.** Change only what the root cause requires. Keep module boundaries intact.
5. **Verify.** The regression test passes; the full `./mvnw test` suite stays green.
6. **Commit** with `fix:` and a message naming the root cause.

## For financial / concurrency bugs
- Reproduce under concurrency (parallel threads hitting the same wallet/idempotency key) — a single-threaded test may pass while the bug remains.
- Verify the invariant directly: balance never negative, refunds never exceed remaining, no double charge.
- Check both the Redisson lock layer and the DB pessimistic lock layer.

## Red flags (stop and rethink)
- "Add a try/catch to make it go away" — that hides the cause.
- "It works now but I'm not sure why" — keep digging.
- Editing an already-applied Flyway migration — add a new one instead.
