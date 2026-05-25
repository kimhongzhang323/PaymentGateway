# Architecture Principles

KimPay is a Maven multi-module Spring Boot application. Respect module boundaries — they are the backbone of the design.

## Module dependency direction

```
payment-api  ──▶ payment-core ──▶ payment-domain
     │               │                  ▲
     └──────▶ payment-common ───────────┘
```

- **payment-domain** — JPA entities + enums only. **No dependencies on other internal modules.** Keep it free of Spring service logic.
- **payment-core** — business logic (services), repositories, infrastructure interactions (Redis, Redisson, Kafka publisher interface). Depends on domain + common.
- **payment-common** — shared utilities: encryption, key management, QR, logging helpers. Used by api and core. No dependency on core.
- **payment-api** — Spring Boot entry point: controllers, security filter chain, transport adapters (Kafka publisher impl), config. The only module that boots the app.

**Rule:** dependencies point inward/downward only. Never make `domain` depend on `core`, or `common` depend on `core`.

## Boundaries & interfaces
- Cross-module collaboration goes through interfaces (e.g. `PaymentEventPublisher` lives in core; the Kafka implementation lives in api). This keeps core transport-agnostic and testable.
- A no-op / fallback implementation must be provided for optional infrastructure so the context loads without it. **Define fallbacks as `@Bean` in a `@Configuration` class with `@ConditionalOnMissingBean`** — not as a scanned `@Component` (conditional evaluation on components is order-dependent and unreliable).

## Single responsibility
- One class, one purpose. If a service file grows past a few hundred lines or mixes unrelated concerns (e.g. payments + refunds + QR + wallets), split by responsibility.
- Persist enough context on entities to support the full lifecycle (e.g. store the wallet/payment-method used so a deferred capture can find it).

## State transitions
- Payment status changes go through explicit methods on the entity (`authorize()`, `capture()`, `refund()`...) guarded against illegal transitions. Do not mutate status fields directly from services.

## New phases / subsystems
Each major addition (PSP adapter, ledger, webhooks) gets: a spec in `docs/superpowers/specs/`, a plan in `docs/superpowers/plans/`, and a decision-log entry in `.claude/docs/decision-log.md`.
