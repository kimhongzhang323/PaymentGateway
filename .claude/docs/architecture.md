# Architecture Reference

Quick architectural orientation. The authoritative deep-dive with diagrams is the repo-root `ARCHITECTURE.md`; the principles you must follow are in `.claude/rules/architecture-principles.md`.

## Modules
- **payment-api** — Spring Boot entry point: REST controllers, security filter chain, Kafka publisher implementation, config. Boots the app.
- **payment-core** — business services (`PaymentService`, `QRService`, signature services), repositories, infra interfaces (`PaymentEventPublisher`), Redis/Redisson usage.
- **payment-domain** — JPA entities and enums only; no internal-module dependencies.
- **payment-common** — shared crypto/key-management, QR, logging utilities; used by api and core.

Dependency direction: `api → core → domain`, with `api,core → common → domain`. Never invert.

## Core flows
- **Create payment:** validate → idempotency check (Redis + DB unique) under Redisson lock → persist `AUTHORIZED` → (optional) capture → publish event. Auto-capture for retail/QR.
- **Wallet debit:** Redisson lock on wallet + DB pessimistic lock → balance check → debit + wallet-transaction → status `CAPTURED`.
- **Refund:** validate against remaining amount → record `Refund` → credit wallet if applicable → status `REFUNDED`/`PARTIALLY_REFUNDED`.
- **QR:** encrypted payload (AES-256-GCM) decrypted → merchant validated → routed through create-payment.

## Infrastructure
- PostgreSQL (Flyway migrations), Redis 7 (cache/locks/nonce), Kafka (async events, ordered per `transactionId`).
- Optional infra (Kafka) has a no-op fallback bean so the context loads without it — defined as a `@Configuration @Bean` with `@ConditionalOnMissingBean` (not a conditional `@Component`).

## Roadmap (where things are going)
1. **Phase 1 — Security foundation:** stateless auth (API key + RSA signing), replay protection, validation, non-leaking errors, KMS-selectable keys, log redaction.
2. **Phase 2 — Payment completeness:** `PspConnector` (Stripe test), 3DS, merchant webhooks, inbound PSP webhooks, double-entry ledger, settlement/reconciliation.
3. **Phase 3 — Production readiness:** rate limiting/QPS handling, resilience, full CI/CD, full QA plan, observability.

See `docs/superpowers/specs/` and `docs/superpowers/plans/` for the current spec/plan, and `.claude/docs/decision-log.md` for decisions.
