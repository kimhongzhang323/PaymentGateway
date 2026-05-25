---
name: deploy
description: Use when building, packaging, or deploying KimPay - covers the build, container image, required environment/secrets, migrations, and health verification.
---

# Deploy

Build and ship KimPay safely. Sandbox-grade: test PSP credentials only, never live keys.

## Pre-deploy gate
- `./mvnw clean verify` is green (unit + integration).
- CI quality gates pass: coverage threshold, no high/critical CVEs, image scan clean, Flyway migrations validate.
- New migrations are additive and reviewed.

## Build
- JAR: `./mvnw -pl payment-api -am clean package` (boot jar in `payment-api/target`).
- Image: build via `payment-api/Dockerfile`. Tag with the git SHA. Scan the image (Trivy) before promoting.
- Local stack: `docker compose up` brings up Postgres/Redis/Kafka (see `docker-compose.yml`).

## Required environment (never hard-coded)
- DB: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`
- Redis: `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`
- Kafka: `KAFKA_BOOTSTRAP_SERVERS`, `PAYMENT_KAFKA_ENABLED`
- Crypto: `PAYMENT_KEY_PROVIDER` (`kms` in shared envs), `PAYMENT_ENCRYPTION_KEY_BASE64` (local only), gateway private key
- PSP (Phase 2): Stripe **test** secret/webhook keys
Validate all required vars are present at startup; fail fast if missing.

## Migrate
- Flyway runs on boot (`FLYWAY_ENABLED=true`). For controlled rollouts, run migrations as a separate gated step before rolling app instances.

## Verify after deploy
- `/actuator/health` returns UP (and readiness once observability lands).
- Smoke test: issue a test API key, send a signed test payment, confirm 201 and a ledger/event record.
- Watch payment-failure-rate and latency dashboards against SLOs.

## Rollback
- Redeploy the previous image tag. Forward-fix the database (never destructive down-migrations on prod data).
