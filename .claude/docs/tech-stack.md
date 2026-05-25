# Tech Stack

| Layer | Technology | Notes |
|---|---|---|
| Language | Java (`<java.version>` in parent `pom.xml`, currently **17**) | Docs/README reference 21 — see decision-log; reconcile before relying on 21-only APIs. |
| Framework | Spring Boot 3.5.x | Web MVC, Security, Data JPA, Kafka, Data Redis. |
| Build | Maven (multi-module) via `./mvnw` | Modules: `payment-api`, `payment-core`, `payment-domain`, `payment-common`. |
| Database | PostgreSQL 15 | Schema via Flyway (`payment-api/.../db/migration`). Tests use H2 in PostgreSQL mode. |
| Cache / locks | Redis 7 + Redisson | Idempotency, merchant cache, distributed locks, nonce store. |
| Messaging | Apache Kafka | Async payment events; `transactionId` as partition key. |
| Crypto | AES-256-GCM (field encryption), RSA SHA256 (signatures) | `payment-common/security`. |
| Auth | API key (BCrypt-hashed secret) + RSA request signing | Stateless Spring Security filter chain (Phase 1). |
| PSP (Phase 2) | Pluggable `PspConnector`; Stripe **test** first | Plus `MockAcquirerConnector` for offline/CI. |
| Testing | JUnit 5, Mockito, AssertJ, MockMvc, (Testcontainers planned) | See `.claude/rules/testing-strategy.md`. |
| Containers | Docker + `docker-compose.yml` | Local Postgres/Redis/Kafka. |
| CI | GitHub Actions (`.github/workflows/ci-cd.yml`), Qodana | Expanded in Phase 3. |

## Commands
- Build: `./mvnw clean package`
- Test (all): `./mvnw test`
- Test (module/class): `./mvnw -pl payment-core -am test -Dtest=<Class>`
- Local stack: `docker compose up`
- Run app: `./mvnw -pl payment-api -am spring-boot:run` (or `run.bat` / `run.ps1`)

## Key config knobs (env)
`DB_*`, `REDIS_*`, `KAFKA_BOOTSTRAP_SERVERS`, `PAYMENT_KAFKA_ENABLED`, `PAYMENT_KEY_PROVIDER` (`env`/`kms`), `PAYMENT_ENCRYPTION_KEY_BASE64`, `PAYMENT_KAFKA_TOPIC`, `PORT`.
