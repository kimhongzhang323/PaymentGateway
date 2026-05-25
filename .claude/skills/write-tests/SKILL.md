---
name: write-tests
description: Use when adding or improving test coverage for KimPay - guides choosing the right test level and writing meaningful payment/concurrency/security tests.
---

# Write Tests

Write tests that prove behavior and protect money-correctness. See `.claude/rules/testing-strategy.md` for the full strategy.

## Choose the level
- **Unit** (default): pure logic with mocked collaborators (Mockito). Services, ledger math, crypto, signature/HMAC, masking. No Spring context.
- **`@DataJpaTest`**: repository query methods and mappings.
- **`@SpringBootTest` + MockMvc**: controller wiring, validation, security filters. Runs on H2 (PostgreSQL mode).
- **Testcontainers**: anything needing real Redis/Kafka/Postgres semantics.
- **E2E**: full secured flow with a real RSA keypair and signed request.

## Write good tests
- One behavior per test; descriptive name (`authenticateRejectsWrongSecret`).
- Arrange clean state in `@BeforeEach` (delete in FK-safe order for integration tests).
- Assert with AssertJ on observable outcomes (status code, `{ code }` envelope, persisted state) — not internal exception text.
- Enable security filters when testing auth; existing happy-path integration tests use `addFilters = false`.

## Must-cover for payments
- **Idempotency/concurrency:** duplicate idempotency keys; parallel double-spend on one wallet; replayed nonce. Assert no overdraft / no double charge.
- **Money math:** partial refunds accumulate correctly and never exceed the remaining amount; ledger entries balance.
- **Security:** unauthenticated → 401; bad/replayed signature → 401; invalid input → 400; error bodies leak nothing.
- **Failure paths:** Redis/PSP down → graceful fallback, not an uncaught 500.

## Anti-patterns
- Testing getters/framework code for coverage numbers.
- Asserting on log output or exact messages.
- One giant test that exercises ten behaviors.
