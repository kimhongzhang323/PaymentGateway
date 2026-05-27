# Phase 3a — QPS / Throughput Hardening: Design

**Date:** 2026-05-27
**Status:** Approved (design)
**Phase:** 3a (first slice of Phase 3 — Production Readiness)
**Predecessors:** Phase 1 (security), Phase 2a/b/c (PSP seam, ledger, webhooks)

---

## 1. Goal

Protect the gateway and downstream PSP from overload while preserving money-correctness:

- **Per-API-key rate limiting** so a single merchant cannot exhaust gateway capacity, with a graceful `429 + Retry-After`.
- **Circuit breakers + timeouts around PSP calls** so an unhealthy/slow PSP fails fast with a graceful `503 + Retry-After` instead of tying up request threads or surfacing a 500.

The k6/Gatling load test that *proves* the 1,000 TPS / p99 < 250 ms SLO is **deferred to the 3c QA slice**; 3a delivers the throttling/resilience behavior with unit + integration tests.

## 2. Non-goals (this slice)

- DB-backed per-merchant rate tiers / plans (static config + per-key overrides only — YAGNI for sandbox).
- Per-endpoint rate-limit matrix (one bucket per key covers all endpoints).
- IP-based / unauthenticated rate limiting (auth filter already rejects unauthenticated requests before they reach the limiter).
- Async capture queue / bulkheads (circuit breaker + timeout only this slice).
- The load-test harness and SLO proof (→ 3c).

## 3. Existing surface this builds on

- **Filter chain** (`payment-api/security/SecurityConfig`): `ApiKeyAuthFilter` → `RequestSignatureFilter`. `MerchantPrincipal(merchantId, keyId)` is on the `SecurityContext` after auth.
- **Redisson** client already configured (used for Redisson locks + nonce cache).
- **`PspConnector`** interface in `payment-core/psp` with `MockAcquirerConnector` default fallback (`@ConditionalOnMissingBean`).
- **`ErrorCode`** (payment-common) already defines `RATE_LIMIT_EXCEEDED("SEC-002")` and `SERVICE_UNAVAILABLE("NET-003")` — **no new error codes needed**.
- **`ApiExceptionHandler`** (payment-api) maps exceptions to the `{code, message}` envelope.

## 4. Component 1 — Rate-limit filter

### Placement & keying
- New `RateLimitFilter` in `payment-api/security`, registered **after** `RequestSignatureFilter` so `MerchantPrincipal` is resolved.
- Bucket key: `payment:ratelimit:key:{keyId}` (namespaced per existing Redis convention; constant prefix).
- Public paths exempt (no principal): `/actuator/health`, `/actuator/info`, `/api/webhooks/psp`. If no authenticated principal is present, the filter does not throttle (the request was already going to be rejected by the auth chain).

### Algorithm & config
- **Bucket4j** token bucket backed by **Redisson** (`bucket4j-redisson` / Bucket4j distributed proxy) so limits hold across nodes.
- Static global default via properties:
  - `payment.ratelimit.enabled` (default `true`)
  - `payment.ratelimit.capacity` — burst size (bucket capacity)
  - `payment.ratelimit.refill-tokens` + `payment.ratelimit.refill-period` — sustained rate
  - `payment.ratelimit.overrides.{keyId}` — optional per-key override map
- Defaults chosen so the gateway ships working (e.g. capacity 100, refill 50/sec — tunable; final numbers set in the plan).

### Behavior
- **Allow:** proceed; add `X-RateLimit-Remaining` header.
- **Deny:** short-circuit with **HTTP 429**, body = standard envelope `{ "code": "SEC-002", "message": ... }`, header `Retry-After: <seconds>` computed from the bucket's nanos-to-refill. Written directly by the filter (it runs before `ApiExceptionHandler`).
- **Redis unavailable → FAIL OPEN:** allow the request, log at WARN, do not 500. Rationale: consistent with KimPay's "cache outage falls back, never fails the request" rule; DB pessimistic row locks still guarantee zero overdraft / zero double-charge even when unthrottled, so correctness is not compromised — only abuse-protection is temporarily degraded.

## 5. Component 2 — PSP circuit breaker

### Decorator seam
- A `ResilientPspConnector` decorator bean in `payment-core/psp` wraps the delegate `PspConnector`. The `PspConnector` interface is unchanged; callers are unaffected.
- Wiring: the resilient decorator becomes the primary `PspConnector` bean, delegating to the underlying connector (mock or future real adapter).

### Resilience4j config (per PSP operation)
- **TimeLimiter:** per-call timeout (`payment.psp.resilience.timeout`).
- **CircuitBreaker:** failure-rate threshold, sliding window, `wait-duration-in-open-state`, half-open permitted calls — via `payment.psp.resilience.*` properties.
- Applied around `authorize`, `capture`, `voidAuthorization`, `refund`.

### Behavior when OPEN / on timeout
- Throw a new core `PspUnavailableException`.
- `ApiExceptionHandler` maps it to **HTTP 503**, envelope `{ "code": "NET-003", ... }`, with a `Retry-After` header (derived from `wait-duration-in-open-state`).
- No fallback charge, no partial state mutation, no stack trace leaked to the client.

## 6. Error handling summary

| Condition | Status | Code | Header |
|---|---|---|---|
| Rate limit exceeded | 429 | SEC-002 | `Retry-After` |
| PSP breaker open / timeout | 503 | NET-003 | `Retry-After` |
| Redis down (limiter) | (request proceeds) | — | fail-open, WARN log |

New exception: `PspUnavailableException` (payment-core) → 503 in `ApiExceptionHandler`.

## 7. Module placement (respects dependency direction)

- `RateLimitFilter`, filter registration, `ApiExceptionHandler` mapping → **payment-api**.
- `ResilientPspConnector` decorator, `PspUnavailableException` → **payment-core**.
- New properties classes (`@ConfigurationProperties`) → owning module (api for ratelimit, core/api for psp resilience config).
- No new dependency from `domain` or `common` on `core`.

## 8. Dependencies (new)

- `bucket4j-core` + `bucket4j-redisson` (or Bucket4j's Redisson proxy manager) — distributed token buckets.
- `resilience4j-spring-boot3` (circuit breaker + timelimiter) — aligned with Spring Boot 3.5.x.

Justify in the plan; CI dependency/vuln scanning (3b) must pass. Pin versions compatible with Boot 3.5.x / Java 17.

## 9. Testing strategy (TDD)

**Unit (payment-core / payment-api, no Spring context where possible):**
- Token bucket: consume within capacity, deny when exhausted, refill over time.
- `Retry-After` seconds computation from nanos-to-refill.
- Circuit breaker: opens after failure-rate threshold; half-opens after wait; closes on success; timeout produces `PspUnavailableException`.

**Slice / integration (filters enabled — per testing-strategy rule for new security behavior):**
- Burst past the limit → `429` + `Retry-After` + `{code:"SEC-002"}`; under limit → `200`/`201`.
- Redis-down simulation → fail-open (request proceeds), no 500.
- PSP forced to fail N times → breaker opens → `503` `{code:"NET-003"}` (assert NOT 500, no stack trace in body).
- Recovery: after wait-duration, half-open trial succeeds → breaker closes.

**Conventions:** AssertJ; assert on status code + `{code}` envelope, never internal messages; clean DB state in `@BeforeEach`; prefer Testcontainers for the Redis-backed limiter integration path going forward (H2 for the rest, per existing setup).

## 10. Exit criteria

- A merchant exceeding its configured rate gets `429 + Retry-After + SEC-002`; within-limit traffic is unaffected.
- A failing/slow PSP trips the breaker → callers get `503 + Retry-After + NET-003`, request threads are not held, no 500/stack trace.
- Redis outage degrades gracefully (fail-open), proven by test.
- Unit + integration suite green; no regression in the existing suite.
- (SLO load-test proof tracked in 3c, not a gate for 3a.)

## 11. Decision-log entry (to add on implementation)

Per-API-key Bucket4j+Redisson rate limiting (static config, fail-open on Redis loss) + Resilience4j circuit-breaker/timeout decorator around `PspConnector` (open → 503 NET-003). Load-test SLO proof deferred to 3c.
