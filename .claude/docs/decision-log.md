# Decision Log

Architectural decisions for KimPay. Newest first. Each entry: context → decision → consequences.

---

## 2026-05-27 — Phase 3b: CI quality gates

**Context:** Production-readiness needs CI that blocks unsafe merges. Prior CI was a single H2 build + advisory Qodana + an unpushed docker build. Sandbox-grade with no live deploy target, so 3b stops at CI gates — environment deploy/staging/prod promotion and image publish are deferred. `feat/phase3b-cicd`.

**Decision:** Restructured `.github/workflows/ci-cd.yml` into six parallel jobs. **Hard-fail gates:** `build-test` (`./mvnw clean verify -B` — Surefire + Failsafe + JaCoCo coverage `check` on payment-core), `migration-validation` (Flyway `migrate` against a real Postgres service — catches PostgreSQL-only drift that H2 tests miss; the service's `POSTGRES_DB=payment_gateway` pre-creates the DB since Flyway does not), `dependency-scan` (`trivy fs`, HIGH/CRITICAL, `exit-code 1`), `secret-scan` (Gitleaks, full history). **Advisory:** Qodana (`continue-on-error`) and `image-build-scan` (`trivy image`, SARIF upload, no block, no registry push). Tooling = Trivy-centric + Java-native (Approach A): rejected OWASP Dependency-Check (slow/flaky NVD, needs API key) and GitHub-native dependency-review (PR-diff only — would miss the existing Bucket4j/Resilience4j CVEs). `.trivyignore` is the documented+dated escape hatch (starts comment-only). The Failsafe `*IT` seam is wired now (no `*IT` exist yet); Testcontainers migration of the H2 suite is deferred to 3c.

**Coverage gate — ratchet-from-baseline:** measured payment-core line coverage baseline is **56.54%** (415/734 lines). Per the spec policy (pin at 80% if already there, else at the measured floor), the JaCoCo `check` minimum is set to **0.56**. This blocks regression from day one; a 3c follow-up ratchets toward 80% as test coverage is added.

**Build deviation:** the Flyway plugin's `flyway-database-postgresql` *dependency* needs an explicit `<version>` — Maven does not apply the parent's `dependencyManagement` to plugin-level dependencies. Used `${flyway.version}` (parent-managed, resolves to 11.7.2) so it stays in sync with `flyway-core`.

**Manual step (not in-repo config):** the four hard-fail jobs (`build-test`, `migration-validation`, `dependency-scan`, `secret-scan`) must be added as **required status checks** on `main` in GitHub branch-protection settings for the gates to actually block merges. Until then they run but do not enforce.

**Consequences:** Closes 3a-audit **M4** (Bucket4j 8.14.0 / Resilience4j 2.3.0 now CVE-scanned). 3c follow-ups: ratchet coverage from 0.56 toward 0.80; Testcontainers integration profile + migrating infra-dependent tests off H2. Image publish to GHCR and any environment deploy remain deferred until real infra exists.

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

## 2026-05-26 — Phase 2a: pluggable PSP adapter seam + lifecycle fix
**Context:** Card payments only validated the stored method (no real charge), and deferred capture could not move money because the wallet/method context was not persisted on the transaction.
**Decision:** Introduce a transport-agnostic `PspConnector` interface in payment-core (mirroring `PaymentEventPublisher`), with a deterministic `MockAcquirerConnector` as the default `@ConditionalOnMissingBean` fallback. Persist `wallet_id` and `psp_reference` on `Transaction` (Flyway V4). Route card-path authorize/capture/void/refund through the connector; wallet-path keeps the internal ledger logic. Deferred capture now debits the persisted wallet or captures the stored PSP reference. The concrete `StripeConnector` (test mode) is deferred to Phase 2b — this slice ships the seam and stays green offline/CI (51 tests, 0 failures).
**Consequences:** Card flows are now PSP-mediated and reconcilable by `psp_reference`; the long-standing deferred-capture TODO is closed. Mock declines any amount whose minor units equal .01 for decline-path testing. Phase 2b adds the real Stripe adapter (selected by property), 3-D Secure, inbound Stripe webhook verification, outbound merchant webhooks, and the double-entry ledger.

## 2026-05-26 — Phase 2b: double-entry ledger

**Context:** Existing money-movement records (`WalletTransaction`, `TransactionFee`, `Refund`) are single-entry rows that don't enforce global debit/credit balance. The roadmap requires a ledger that makes it possible to detect drift between gateway state and PSP/wallet state.

**Decision:** Add a parallel double-entry ledger (`LedgerAccount`, `JournalEntry`, `JournalLine`) in `payment-domain`/`payment-core`. Posting via `LedgerService.post(LedgerPostingRequest)` is called inside the existing `@Transactional` capture and refund methods so the ledger write is atomic with the money movement. Accounts are per-owner (WALLET:{id}, MERCHANT:{id}) or system singletons (SYS:PSP_CLEARING, SYS:FEE_REVENUE, SYS:GATEWAY_CASH), seeded by Flyway V5. Balances are materialized and locked in id-sorted order (deadlock-safe pessimistic writes). Existing `WalletTransaction` rows are unchanged. AUTHORIZE and VOID produce no ledger entries (no money moves). Fee revenue is not reversed on refund (gateway keeps fee — explicit policy). `trialBalance()` returns true iff global Σdebits == Σcredits.

**Consequences:** Any future reconciliation job or settlement report reads `journal_lines` as the source of truth. Cross-currency, settlement/payout records, and PSP reconciliation-against-live-data are deferred to later Phase 2b slices. Ledger account balances use natural-side orientation: LIABILITY accounts (WALLET, MERCHANT) increase on credit and decrease on debit — so wallet balances are negative after spending (representing net outflow), which is conventional double-entry accounting.

## 2026-05-27 — Phase 2c: from-scratch webhooks (inbound PSP reconciliation + outbound merchant delivery)

**Context:** Phase 2 needed PSP-driven state reconciliation and merchant notifications. We deliberately dropped the planned Stripe SDK and built everything in-house against the existing `MockAcquirerConnector` — no external PSP dependency.

**Decision:** Two slices on `feat/phase2b-ledger`. **Inbound:** `POST /api/webhooks/psp` (permitAll + excluded from the RSA merchant filter) verifies HMAC-SHA256 over `timestamp + "." + body` against a shared `PSP_WEBHOOK_SECRET` (fail-fast on blank secret, ±300s window, constant-time compare) and reconciles Transaction state (CAPTURED/VOIDED/REFUNDED). Idempotency is two-layer: `existsByEventId` plus a DB unique constraint (V6) caught as `DataIntegrityViolationException` to close the TOCTOU race. **Outbound:** merchant endpoint registration API (HTTPS-only at registration, secret encrypted at rest via `EncryptedStringConverter`, shown once), `WebhookDelivery` rows fanned out by the Kafka `PaymentEventListener`, dispatched by a `@Scheduled` DB poller (every 10s, batch 50) with exponential backoff (30s→5m→30m→2h→8h) and dead-lettering after 5 attempts. Outbound signing mirrors inbound (`X-Kimpay-Signature: sha256=<hex>`). HMAC, signing, retry-math, and dispatch are unit-tested; WireMock covers outbound HTTP. 42 tests green.

**Decision (pre-merge review fixes):** `GET /api/webhook-endpoints` returns a `WebhookEndpointView` projection that omits the secret (was leaking the decrypted plaintext via the raw JPA entity); `dispatch()` is `@Transactional`; malformed inbound JSON maps to 400 not 500; dispatch re-checks HTTPS as defense-in-depth (allowing `http://localhost` for tests) and marks non-HTTPS deliveries DEAD; the Kafka listener fan-out is `@Transactional` on the public entry point (self-invocation would have made it a no-op on the private method).

**Consequences:** KimPay reconciles PSP state and notifies merchants at-least-once with signed, retried, dead-letterable deliveries, with no third-party PSP dependency. Deferred: a distributed lock on the scheduler for multi-node (single-node sandbox is fine); modeling `WebhookDelivery.status` as an enum rather than a String; controller-level tests for `WebhookEndpointController`.

## 2026-05-27 — Phase 3a: per-key rate limiting + PSP circuit breaker

**Context:** Production-readiness needs overload protection (per-merchant throttling, resilience to a slow/unhealthy PSP) without ever compromising money-correctness. First slice of Phase 3; `feat/phase3a-qps`.

**Decision:** **Rate limiting** — a `RateLimitFilter` registered after `RequestSignatureFilter` keys per-API-key **Bucket4j** token buckets over the existing **Redisson** client (distributed `ProxyManager<String>`, key `payment:ratelimit:key:{keyId}`). Static config (`payment.ratelimit.*`) with optional per-key overrides; over-limit → 429 + `Retry-After` + `SEC-002`. **Fails open** on Redis loss (WARN, allow) — DB pessimistic locks still guarantee zero overdraft/double-charge, so only abuse-protection degrades, not correctness. **PSP resilience** — a `ResilientPspConnector` decorator (the `@Primary` `PspConnector`, wrapping a delegate bean named `pspDelegate`) applies **Resilience4j** CircuitBreaker + TimeLimiter (bounded executor) per call; breaker-open/timeout → `PspUnavailableException` → 503 + `Retry-After` + `NET-003`, no thread held, no stack-trace leak. Reuses existing `SEC-002`/`NET-003` error codes (no new codes). Module placement: filter + Bucket4j config in `payment-api`; decorator + exception + resilience config in `payment-core`.

**Decision (build/impl deviations from the plan):** Bucket4j's planned coordinates don't exist — pinned `com.bucket4j:bucket4j_jdk17-core` / `bucket4j_jdk17-redisson` **8.14.0** (8.11+ ships JDK-targeted artifacts; namespace `io.github.bucket4j`). Resilience4j BOM `2.3.0`. PSP-breaker integration test needed `spring.main.allow-bean-definition-overriding=true` because a test `@Bean("pspDelegate")` and the production `@ConditionalOnMissingBean(name="pspDelegate")` mock both register under that name (the conditional evaluates before override resolution). `ResilientPspConnector` re-interrupts the thread on `InterruptedException` (per coding-standards).

**Consequences:** Single-merchant floods are throttled cluster-wide; an unhealthy PSP fails fast instead of exhausting request threads. Full suite green (payment-core 41, payment-api 48, 0 failures/errors), including filters-enabled `RateLimitIntegrationTest` (3rd request → 429, only 2 ledger entries posted) and `PspCircuitBreakerIntegrationTest` (breaker-open → 503/NET-003, no leakage). **Deferred:** load-test SLO proof (→ Phase 3c); DB-backed rate tiers and per-endpoint matrices (YAGNI for sandbox); hardening the `pspDelegate` swap seam so a real adapter can replace the mock without `allow-bean-definition-overriding` (revisit when `StripeConnector` lands).

## 2026-05-27 — Phase 3a security audit: follow-ups (gated)

**Context:** A read-only security/correctness audit of the whole `feat/phase3a-qps` branch found no Critical and no reachable High for the sandbox slice (mock PSP does no I/O), but flagged items that become real before a live adapter or shared environment.

**Decision:** Merge 3a as-is (audit endorsed for the sandbox slice) and track these as explicit prerequisites:
- **H1 — gate before any real PSP adapter:** the `ResilientPspConnector` runs the delegate on a separate executor thread (no transaction/`SecurityContext`/MDC propagation) and the `TimeLimiter` cancels the future, not the network call — so a timed-out `authorize`/`capture`/`refund` may still complete at the PSP. Before wiring a real adapter: pass idempotency keys to every PSP op, add a "503-but-PSP-succeeded" reconciliation/repair path, treat timeout as *unknown outcome*, and propagate (or explicitly pass) any context the delegate needs.
- **M3 — before high-TPS:** `pspExecutor` uses an unbounded `LinkedBlockingQueue`; under a slow-but-not-failing PSP it grows unboundedly. Bound the queue (+ `CallerRunsPolicy` or reject→`PspUnavailableException`) or add a Resilience4j Bulkhead; size to PSP I/O concurrency, not CPU count.
- **M1 — should-fix:** add a test asserting transaction state after a `PspUnavailableException` mid-capture (rollback-only vs `markFailed()` save).
- **M4 — before shared env:** run dependency/vuln scanning on Bucket4j 8.14.0 / Resilience4j 2.3.0 (CVE status currently unverified); this is Phase 3b CI scope.

**Consequences:** No code change for the sandbox slice; correctness/DoS hazards are latent behind the mock and documented as hard gates for later phases. Lows accepted: rate-limit fail-open (DB locks still enforce correctness), graceful executor shutdown, message-from-`ErrorCode` drift (currently identical).
