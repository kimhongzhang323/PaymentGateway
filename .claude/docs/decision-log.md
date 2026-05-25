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
