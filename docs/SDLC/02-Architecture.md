# 2. Architecture

## 2.1 High-Level
- API (Spring Boot) → Core (services) → Domain (JPA) → Supabase (PostgreSQL)
- AuthN/AuthZ (Spring Security), Encryption (AES-GCM)

## 2.2 Modules
- payment-api: REST, config, controllers
- payment-core: business services
- payment-domain: entities and repositories (future)
- payment-common: shared utils, errors, encryption

## 2.3 Data Flow
1. API receives request → validates → calls service
2. Service executes business logic → persists via JPA
3. DB changes → logs/audits → returns response

## 2.4 Cross-Cutting
- Logging: structured, MDC
- Config: .env + application.yml
- Migrations: Flyway
- Observability: logs + health endpoints

