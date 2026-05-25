---
name: implement-feature
description: Use when adding a new feature or endpoint to KimPay - guides spec-driven, TDD implementation that respects module boundaries and payment security rules.
---

# Implement Feature

Disciplined workflow for adding functionality to the KimPay payment gateway.

## Before writing code
1. Confirm there is a spec (`docs/superpowers/specs/`) and plan (`docs/superpowers/plans/`). If not, brainstorm and plan first — do not improvise a payment feature.
2. Identify which module each change belongs to (see `.claude/rules/architecture-principles.md`):
   - Entity/enum → `payment-domain`
   - Business logic / repository / infra interface → `payment-core`
   - Controller / security / transport impl / config → `payment-api`
   - Shared utility (crypto, masking) → `payment-common`
3. Check the relevant rules: `security.md`, `api-design.md`, `testing-strategy.md`, `path-scoped/backend/database.md`.

## Implementation loop (TDD, per task)
1. Write the failing test (unit first; integration only where wiring matters).
2. Run it — confirm it fails for the expected reason.
3. Write the minimal code to pass. Constructor injection, records for DTOs, `BigDecimal` for money.
4. Run the test — confirm green. Run the module suite.
5. Commit with a conventional message (`feat:`, `fix:`, `test:`...).

## Payment-specific checklist
- [ ] Money uses `BigDecimal`; currency validated as 3-letter ISO.
- [ ] Mutating endpoint authenticated; owner-scoped; supports idempotency.
- [ ] No PAN/secret/PII logged; sensitive fields encrypted at rest.
- [ ] State transitions go through entity methods, guarded against illegal moves.
- [ ] Concurrency on balances protected by Redisson lock + DB pessimistic lock.
- [ ] DTOs validated at the boundary; errors return the `{ code, message }` envelope.
- [ ] New error conditions added to the `ErrorCode` enum.
- [ ] Prod Flyway migration written; entity mapping matches schema.

## Done means
All new tests pass, the full `./mvnw test` suite is green, the change is committed, and any new architectural decision is recorded in `.claude/docs/decision-log.md`.
