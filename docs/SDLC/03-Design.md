# 3. Design

## 3.1 Domain Model
- Entities: User, Role, Permission, Merchant, PaymentMethod, Transaction, Wallet, etc.
- Base entities: AbstractAuditedEntity, AbstractCreatedAtEntity

## 3.2 API Design
- RESTful endpoints (JSON)
- Idempotency keys for payment initiation
- Error handling via ErrorCode enum

## 3.3 Persistence
- JPA mappings to Postgres tables
- Flyway migrations for schema

## 3.4 Security Design
- AES-GCM encryption via EncryptionService
- RLS (Supabase) for DB-level access (future)

## 3.5 Extensibility
- Provider adapters for payment gateways (future)

