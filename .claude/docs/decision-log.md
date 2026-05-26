# Decision Log

Architectural decisions for KimPay. Newest first. Each entry: context → decision → consequences.

---

## 2026-05-25 — Roadmap: sandbox-grade, security-first, phased
**Context:** Goal is an industry-competitive, *secured* payment gateway. Current code is a clean demo missing auth, real rails, ledger, and ops maturity.
**Decision:** Target **sandbox-grade** (real PSP test integrations, no live money), backend-only. Sequence: **Phase 1 security → Phase 2 payment completeness → Phase 3 production readiness** (incl. QPS handling, full CI/CD, full QA plan). Each phase is spec → plan → implementation.
**Consequences:** Security foundation precedes feature work so later features inherit it. Spec: `docs/superpowers/specs/2026-05-25-payment-gateway-roadmap-design.md`.

## 2026-05-25 — PSP integration via pluggable adapter
**Context:** Need real rails without coupling to one vendor.
**Decision:** Define a `PspConnector` interface; implement `StripeConnector` (test mode) first and a `MockAcquirerConnector` for offline/CI. (Phase 2.)
**Consequences:** Vendor-swappable, testable seam; slight upfront abstraction cost.

## 2026-05-25 — Phase 1 auth: API key (BCrypt) + RSA request signing
**Context:** Endpoints were effectively unguarded (Spring Security on classpath, no `SecurityConfig`).
**Decision:** Stateless filter chain. Authenticate via `Authorization: Bearer <keyId>:<secret>` (secret stored only as BCrypt hash). Verify request integrity with the existing RSA signature services over `timestamp + "." + nonce + "." + body`; replay protection via Redis nonce + ±300s window.
**Consequences:** Reuses existing crypto; merchants must sign mutating requests. Admin JWT deferred until admin endpoints exist (YAGNI).

## 2026-05-25 — No-op event publisher must be a @Configuration @Bean
**Context:** Baseline test suite failed to load the Spring context: `NoopPaymentEventPublisher` used `@ConditionalOnMissingBean` on a scanned `@Component`, which evaluates in classpath order and unreliably skipped the bean, leaving zero `PaymentEventPublisher` beans when Kafka is disabled (test profile).
**Decision:** Provide optional-infra fallbacks as `@Bean` methods in a `@Configuration` class with `@ConditionalOnMissingBean` — never as conditional `@Component`s. Fixed as "Task 0" before Phase 1 implementation.
**Consequences:** Context loads reliably with infra disabled; pattern codified in `architecture-principles.md`.

## 2026-05-25 — Security audit findings folded into Phase 1
**Context:** A security audit of the wired auth chain (after plan Task 6) found gaps the original 12 tasks missed.
**Decision:** Add to Phase 1 scope: **C-1** fix request-body re-read via a `CachedBodyHttpServletRequest` (ContentCachingRequestWrapper consumes the stream → empty controller body); **M-3/H-1** harden the canonical signing string to `method.path.timestamp.nonce.base64(sha256(body))` and enforce signing for all non-safe methods (incl. PATCH); **H-2** bound request body size; **C-2** enforce object-level authorization (merchant may only act on its own transactions; mutators return 404 on mismatch). Captured as Task 5R and Task 13 in the Phase 1 plan.
**Consequences:** Canonical signing string changes before any external merchant integrates (no compatibility cost now). Deferred (low risk): BCrypt timing oracle (L-1), malformed-Base64 → 401 not 500 (L-3), per-merchant nonce namespace (M-2).

## 2026-05-25 — Java version discrepancy (open)
**Context:** Parent `pom.xml` sets `<java.version>17</java.version>`, but `README`/`ARCHITECTURE.md` state Java 21.
**Decision:** Treat 17 as authoritative until reconciled; do not use Java 21-only APIs. Reconcile (bump pom to 21 or correct docs) in a dedicated change.
**Consequences:** Avoids build/runtime surprises; flagged for follow-up.

## 2026-05-26 — Phase 1 Security Foundation complete
**Context:** All 12 Phase 1 tasks plus the audit addendum (Tasks 5R, 13) are implemented, tested, and committed on `feat/phase1-security-foundation`. Full suite green (38 tests, 0 failures/errors); an end-to-end `SecuredPaymentE2ETest` exercises the full chain (Bearer auth → RSA signing → body-tamper rejection → Redis nonce replay rejection) with filters enabled.
**Decision:** Document the hardened canonical signing string and auth scheme in `docs/security/authentication.md` and `ARCHITECTURE.md`. Make the encryption key provider selectable in-bean (`payment.encryption.key-provider` = `env`/`kms`) rather than via `@ConditionalOnProperty`, to avoid pulling `spring-boot-autoconfigure` into the deliberately-lightweight `payment-common` module. Harden `server.error.include-message`/`include-binding-errors`/`include-stacktrace` to `never`.
**Consequences:** Phase 1 ready for review/merge. KMS path is wired but `KmsKeyProvider` is still a stub (throws `UnsupportedOperationException`) — must be implemented before any non-`env` deployment. Deferred low-risk audit items (L-1 BCrypt timing, L-3 malformed-Base64 → 500, M-2 nonce namespace) remain open for a follow-up.

## 2026-05-26 — Pre-merge audit fixes (H-A, M-A)
**Context:** A final security audit of the whole branch (before PR) found the `/scan` endpoint still lacked object-level authorization (the QR's merchant was never compared to the authenticated merchant → cross-merchant payment forging / IDOR), and that `ApiExceptionHandler` echoed `IllegalArgumentException` messages while missing transactions returned 400 — letting a caller distinguish "transaction absent" (400, message leaked) from "exists but not yours" (404), an enumeration oracle.
**Decision:** `processQRPayment` now takes the authenticated merchantId and enforces it matches the QR's merchant (404 on mismatch). Missing transactions throw a new core `ResourceNotFoundException` mapped to the SAME 404 envelope as an ownership failure. `handleBadRequest` returns the static `INVALID_REQUEST` message (detail logged server-side only). Regression tests added (cross-merchant scan rejected, own scan succeeds, non-existent id → 404).
**Consequences:** Phase 1 closes its last reachable High; no known Critical/High remain. Full suite 41/41 green. Deferred lows from the audit (L-A min RSA key size on stored keys, L-B chunked-body size enforced only post-read, L-C fail-fast on empty env key, L-D default DB password in yml) tracked for a follow-up hardening pass.
