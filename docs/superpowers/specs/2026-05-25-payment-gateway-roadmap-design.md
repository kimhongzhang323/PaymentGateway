# KimPay → Industry-Competitive Payment Gateway: Roadmap Design

**Date:** 2026-05-25
**Status:** Approved (sequencing + scope)
**Type:** Multi-phase roadmap. Each phase below is decomposed into its own spec → implementation plan before any code is written.

---

## 1. Goal & Framing

Evolve KimPay from a clean-but-incomplete demo into a **sandbox-grade, industry-shaped payment gateway**: production-shaped architecture and security, integrated with real payment rails in **test/sandbox mode** (no live money).

**Confirmed decisions:**

| Decision | Choice |
|---|---|
| Ambition | Sandbox-grade product (real PSP test integrations, test money only) |
| Surface area | Backend API only (no UI/dashboard, no hosted checkout) |
| Payment rails | Pluggable `PspConnector` adapter; **Stripe test mode** as first implementation |
| Sequencing | **Security-first → Payment completeness → Production readiness** (Option A) |
| Delivery | Full roadmap, phased; QPS handling, complete CI/CD, and full QA plan are first-class workstreams in Phase 3 |

## 2. Current State (baseline)

- **Stack:** Java 21, Spring Boot 3.5.7, multi-module Maven (`payment-api`, `payment-core`, `payment-domain`, `payment-common`), PostgreSQL + Flyway, Redis (idempotency, caching, Redisson locks), Kafka events.
- **Implemented:** AES-256-GCM field encryption, merchant asymmetric-key signature services, wallet debit with Redisson + DB pessimistic double-locking, partial refunds, QR payments, idempotency via Redis + DB fallback.
- **Gaps:** `spring-boot-starter-security` is on the classpath but **no `SecurityConfig` exists** — endpoints are effectively unguarded / behind default basic auth. No real money movement (transactions are authorized in-DB only). No card tokenization, no 3DS, no merchant webhooks, no double-entry ledger / settlement / reconciliation, no rate limiting, no observability stack, thin tests. `capturePayment` cannot locate the wallet for a deferred capture (payment context not persisted on the transaction).

## 3. Non-Goals (explicitly out of scope)

- Real money movement / live card networks; production PSP credentials.
- Formal PCI-DSS certification or audit (we target SAQ-A *posture* and document scope only).
- KYC / AML / sanctions screening flows.
- GDPR data-subject automation (export/erasure tooling).
- Merchant dashboard, hosted checkout page, or any UI.
- Multi-region / multi-tenancy beyond the existing merchant model.

## 4. Default SLOs / Targets (proposed; revisit per phase)

- Sustained **1,000 TPS** on payment-create under load test, gateway-internal **p99 < 250 ms** (excluding upstream PSP latency).
- **100%** idempotency / double-spend correctness under concurrency tests (zero overdrafts, zero double charges).
- Availability target **99.9%**; graceful degradation when Redis or PSP is unavailable.
- CI quality gates: line coverage ≥ 80% on `payment-core`, zero high/critical vulnerabilities, all migrations validated.

---

## 5. Phase 1 — Security Foundation

**Objective:** No feature is built on an unauthenticated base. Lock down API, data, and secrets.

- **Authentication & authorization**
  - Real `SecurityConfig` (stateless filter chain).
  - Merchant API keys: public `pk_*` / secret `sk_*` pairs, secret hashed at rest (BCrypt/Argon2), prefix-indexed lookup.
  - HMAC request signing filter using existing signature services (`GatewaySignatureService`, `SignatureVerificationService`).
  - JWT/session auth for internal/admin endpoints; enforce existing `Role`/`Permission` model.
- **Request integrity:** replay protection (timestamp window + nonce cache in Redis); idempotency enforced at filter layer, not just in service.
- **Secrets & key management:** move `PAYMENT_ENCRYPTION_KEY` from env to the `KmsKeyProvider` path; versioned ciphertext + key rotation; envelope encryption.
- **PCI posture:** never persist PANs (tokenize via PSP); redact card/secret data in logs and `digest.log`; document SAQ-A scope.
- **Input hardening:** Jakarta Bean Validation on all DTOs; global error contract that leaks no internals; explicit CORS policy.

**Exit criteria:** every endpoint requires valid auth; authz matrix test passes; secrets resolved via KMS provider; no sensitive data in logs.

## 6. Phase 2 — Payment Completeness

**Objective:** Make payments real (in sandbox) and money-correct.

- **Pluggable PSP adapter:** `PspConnector` interface — `createPaymentIntent`, `authorize`, `capture`, `void`, `refund`, `tokenize`. Implementations: `StripeConnector` (test mode), `MockAcquirerConnector` (offline/CI).
- **Card flow + 3-D Secure:** PaymentIntent-style flow, tokenization, SCA/3DS challenge handling and resumption.
- **Outbound merchant webhooks:** signed payloads, retries with exponential backoff, dead-letter queue, replayable delivery log (fed by existing Kafka events).
- **Inbound PSP webhooks:** verify Stripe signatures; reconcile async state transitions (e.g. async capture/refund settle).
- **Money correctness:** double-entry **ledger** (every movement balances), settlement & payout records, reconciliation job (gateway vs PSP), fee + currency handling.
- **Lifecycle hardening:** persist payment context (wallet/method) on `Transaction` to fix deferred `capturePayment`; explicit state-machine guards on all transitions.

**Exit criteria:** end-to-end Stripe-test card payment with 3DS; refunds reconcile in ledger; merchant receives signed webhook; reconciliation job reports zero drift on test data.

## 7. Phase 3 — Production Readiness

### 7a. QPS / Throughput Handling
- Per-API-key rate limiting & quotas (Bucket4j + Redis), burst control, `429` with `Retry-After`.
- Resilience4j circuit breakers / bulkheads / timeouts around PSP calls; async capture queue to absorb spikes.
- Tune HikariCP pool, Tomcat threads, Redis pipelining; prove the 1,000 TPS / p99 target with load tests.

### 7b. Complete CI/CD Pipeline (extend `.github/workflows/ci-cd.yml`)
- Stages: build → unit → integration (Testcontainers: Postgres/Redis/Kafka) → static analysis (Qodana) → SAST + dependency + secret scanning → container build & image scan (Trivy) → contract tests → deploy staging → smoke tests → **gated** prod deploy.
- Quality gates: coverage threshold, no high/critical vulns, migration validation, fail-fast.

### 7c. Full QA Test Plan
- **Test pyramid:** unit (services, ledger math, crypto), integration (Testcontainers full slices), contract (PSP adapter + webhook schemas), e2e happy/failure paths.
- **Concurrency/idempotency:** parallel double-spend on wallet, duplicate idempotency keys, lock contention.
- **Security:** authz matrix, signature/replay rejection, fuzz/negative inputs.
- **Non-functional:** load / soak / spike (k6 or Gatling); chaos (PSP down, Redis down → DB fallback); failover.
- **Test data & envs:** seed fixtures, sandbox config, coverage reporting wired into CI gates.

### 7d. Observability
- Structured logging (extend logback), Micrometer + Prometheus metrics, OpenTelemetry tracing across PSP/Kafka, health/readiness probes, alerting on payment-failure-rate SLO breach, immutable audit trail.

**Exit criteria:** load test meets SLOs; CI/CD pipeline green with all gates enforced; QA suite covers pyramid + concurrency + security + chaos; dashboards and alerts live.

## 8. Cross-Cutting (throughout)
- ADRs for major decisions; keep `ARCHITECTURE.md` current.
- OpenAPI spec + generated client SDKs; merchant onboarding & API docs.

## 9. Execution Model
Each phase is specced and planned independently in order. **Immediate next step after this roadmap is approved: write the full Phase 1 (Security Foundation) spec**, then its implementation plan.
