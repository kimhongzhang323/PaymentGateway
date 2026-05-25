# Performance & Concurrency

KimPay targets sustained high TPS with strict money-correctness. Performance work must never compromise correctness.

## Targets (SLOs)
- Sustained ~1,000 TPS on payment-create under load; gateway-internal p99 < 250ms (excluding upstream PSP latency).
- 100% correctness under concurrency: zero overdrafts, zero double charges.
- Graceful degradation when Redis or the PSP is unavailable.

## Concurrency model (two-layer defense)
1. **Application layer (fast):** Redisson distributed locks (`payment:lock:wallet:{id}`, `payment:lock:idempotency:{key}`) serialize across nodes. Use bounded wait + lease times; always release in `finally`.
2. **Database layer (absolute):** pessimistic row locks (`SELECT ... FOR UPDATE` via `findWithLock...`) guarantee correctness even if the cache fails.

Never rely on a single layer for financial mutations.

## Caching (Redis)
- Cache stable lookups (merchant existence) with explicit TTLs; namespace keys `payment:...`.
- Idempotency keys cached with a 24h TTL, backed by a DB unique constraint as the source of truth.
- Treat the cache as an optimization: a cache miss or Redis outage must fall back to the DB, not fail the request.

## Async offloading
- Keep the request thread doing only critical validation + the DB commit. Offload notifications, audit fan-out, and analytics to Kafka consumers.
- Publishing must be non-blocking and must not fail the core transaction.

## Database
- Tune HikariCP pool size to the DB's capacity; don't oversize per-instance pools.
- Use Hibernate batching (`batch_size`, ordered inserts/updates) for bulk writes.
- Index high-cardinality FKs and status columns used in queries.

## Rate limiting (Phase 3)
- Per-API-key token bucket (Bucket4j + Redis). Return `429` with `Retry-After`. Protect both the gateway and downstream PSP quotas.

## Verify, don't guess
Prove throughput/latency claims with load tests (k6/Gatling) against the stated SLOs before asserting them.
