# Database & Persistence Rules (backend)

Applies to JPA entities, repositories, and Flyway migrations.

## Migrations (Flyway)
- All schema changes go through versioned Flyway scripts in `payment-api/src/main/resources/db/migration/` named `V<n>__<description>.sql`.
- **Never edit an already-applied migration.** Add a new one.
- Migrations are PostgreSQL-flavored (prod). Tests use H2 in PostgreSQL mode with `ddl-auto: create-drop` and Flyway disabled, so new entities auto-create in tests — but you still must write the prod migration.
- `ddl-auto` is `validate` in prod: the entity mapping must exactly match the migrated schema (column names, nullability, length).

## Entities
- Extend the existing base classes (`AbstractCreatedAtEntity` / `AbstractAuditedEntity`) for timestamp columns; don't redeclare `created_at`/`updated_at`.
- Money columns: `NUMERIC`/`DECIMAL` with explicit precision and scale — never floating point.
- Sensitive columns use `@Convert(converter = EncryptedStringConverter.class)`; size them for ciphertext, not plaintext.
- Use `GenerationType.IDENTITY` with `BIGSERIAL`/`BIGINT` PKs, matching existing entities.

## Repositories
- Spring Data interfaces in `payment-core/repository`. Derive query methods by name where possible.
- For financial mutations, provide pessimistic-lock finders (e.g. `findWithLockByIdAndUserId`) using `@Lock(LockModeType.PESSIMISTIC_WRITE)`.
- Scope queries by owner (userId/merchantId) to prevent cross-tenant access.

## Transactions
- Annotate service methods with `@Transactional` (read-only for queries). Keep transactions short; do not hold a DB transaction open across a slow external (PSP) call.
- Distributed lock acquisition wraps the transaction, not the reverse, for wallet debits.

## Indexes & constraints
- Add indexes for FKs and columns used in `WHERE`/`ORDER BY`.
- Enforce invariants in the schema (unique idempotency key, FK references, NOT NULL) — don't rely on application checks alone.
