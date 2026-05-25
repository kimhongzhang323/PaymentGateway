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

## 2026-05-25 — Java version discrepancy (open)
**Context:** Parent `pom.xml` sets `<java.version>17</java.version>`, but `README`/`ARCHITECTURE.md` state Java 21.
**Decision:** Treat 17 as authoritative until reconciled; do not use Java 21-only APIs. Reconcile (bump pom to 21 or correct docs) in a dedicated change.
**Consequences:** Avoids build/runtime surprises; flagged for follow-up.
