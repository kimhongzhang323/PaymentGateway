---
name: code-review
description: Use when reviewing a KimPay change or PR - applies a payment-gateway-specific checklist covering correctness, security, concurrency, and module hygiene.
---

# Code Review

Review KimPay changes against correctness, security, and architecture. Be specific; cite `file:line`. Prioritize findings (Critical / Important / Nit).

## What to review (in priority order)

### 1. Money correctness (Critical)
- `BigDecimal` for all amounts; no float; explicit scale/rounding.
- Refunds cannot exceed remaining; balances cannot go negative.
- State transitions guarded; no direct status mutation.

### 2. Concurrency (Critical)
- Wallet/idempotency mutations protected by Redisson lock **and** DB pessimistic lock.
- Locks released in `finally`; bounded wait/lease; `InterruptedException` re-interrupts.
- Idempotency enforced with a DB unique constraint as source of truth.

### 3. Security (Critical)
- Endpoint authenticated and owner-scoped.
- No PAN/secret/private-key/PII in logs or error responses.
- Secrets hashed (BCrypt) or encrypted; nothing sensitive in source/config defaults.
- Input validated at the boundary; error envelope leaks nothing internal.

### 4. Architecture & hygiene (Important)
- Correct module placement; dependency direction not violated.
- Optional-infra fallbacks via `@Configuration @Bean`, not conditional `@Component`.
- Constructor injection; records for DTOs; new `ErrorCode`s in the enum.
- Prod Flyway migration present and matching the entity mapping.

### 5. Tests (Important)
- New behavior has tests at the right level; concurrency/security/failure paths covered.
- Full suite green; no flaky/time-dependent assertions.

## Output
For each finding: severity, `file:line`, the problem, and a concrete fix. End with an explicit verdict: **Approve**, **Approve with nits**, or **Request changes**.
