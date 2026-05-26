# Phase 2b — Double-Entry Ledger: Design Spec

**Date:** 2026-05-26
**Status:** Approved
**Branch:** feat/phase2b-ledger
**Roadmap ref:** `docs/superpowers/specs/2026-05-25-payment-gateway-roadmap-design.md` §6 (Phase 2, "Money correctness")

---

## 1. Goal & Scope

Introduce a **parallel double-entry ledger** as the authoritative money-movement record for KimPay. Every captured or refunded payment posts a balanced journal entry. The ledger runs alongside the existing `WalletTransaction` records (which remain for wallet balance caching) — it does not replace them.

**In scope:** account model, balanced journal entries, materialized account balances, hooks from `PaymentService`, trial-balance verification, unit + integration tests.

**Explicitly out of scope (later slices):** Stripe adapter, 3-D Secure, inbound/outbound webhooks, settlement/payout records, reconciliation-against-PSP job, cross-currency/FX handling, fee reversal on refund.

---

## 2. Chart of Accounts

A `LedgerAccount` represents one side of money custody, scoped per owner and currency.

| Field | Type | Notes |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `code` | VARCHAR(80) | Unique per currency. Format: `WALLET:{walletId}`, `MERCHANT:{merchantId}`, `SYS:PSP_CLEARING`, `SYS:FEE_REVENUE`, `SYS:GATEWAY_CASH` |
| `owner_type` | VARCHAR(30) | `USER_WALLET`, `MERCHANT`, `SYSTEM` |
| `owner_id` | BIGINT nullable | Null for system accounts |
| `classification` | VARCHAR(20) | `ASSET`, `LIABILITY`, `REVENUE` |
| `currency` | CHAR(3) | ISO 4217 uppercase |
| `balance` | NUMERIC(18,2) | Materialized running balance; locked on every posting |

**Unique constraint:** `(code, currency)`.

**Account types:**
- `WALLET:{walletId}` (LIABILITY) — gateway's obligation to the user who holds this wallet.
- `MERCHANT:{merchantId}` (LIABILITY) — gateway's obligation to pay out to the merchant.
- `SYS:PSP_CLEARING` (ASSET) — funds in transit from card networks via PSP.
- `SYS:FEE_REVENUE` (REVENUE) — gateway fee income.
- `SYS:GATEWAY_CASH` (ASSET) — reserved for future settlement.

**Lifecycle:** Owner accounts (WALLET/MERCHANT) are created lazily on first posting (find-or-create inside the posting transaction, pessimistic lock). System accounts are pre-seeded by the Flyway migration.

---

## 3. Journal Model (Append-Only)

### JournalEntry (header)
| Field | Notes |
|---|---|
| `id` BIGSERIAL PK | |
| `transaction_id` BIGINT NOT NULL | FK → transactions(id); indexed |
| `event_type` VARCHAR(20) | `CAPTURE` or `REFUND` |
| `description` VARCHAR(255) | |
| `created_at` TIMESTAMP | |

### JournalLine
| Field | Notes |
|---|---|
| `id` BIGSERIAL PK | |
| `journal_entry_id` BIGINT NOT NULL | FK → journal_entries(id) |
| `account_id` BIGINT NOT NULL | FK → ledger_accounts(id) |
| `direction` VARCHAR(6) | `DEBIT` or `CREDIT` |
| `amount` NUMERIC(18,2) NOT NULL | Always > 0 |
| `currency` CHAR(3) NOT NULL | Must match account currency |

**Immutability:** Lines are never updated or deleted. Corrections are made via reversing entries.

**Invariant (enforced before write):**
- Σ debit amounts == Σ credit amounts.
- All amounts > 0.
- Single currency per entry.
- ≥ 2 lines.

---

## 4. Balance Semantics

Balance is stored in the account's **natural-side orientation**:

```
delta = (direction == DEBIT ? +1 : -1) * (classification == ASSET ? +1 : -1) * amount
```

- ASSET accounts: debit increases balance, credit decreases.
- LIABILITY/REVENUE accounts (credit-normal): credit increases balance, debit decreases.

Examples:
- `WALLET:{id}` (LIABILITY): credit +100 → balance rises by 100 (user has funds). Debit 50 on capture → balance falls by 50 (user spent).
- `MERCHANT:{id}` (LIABILITY): credit on capture → balance rises (we owe merchant more).
- `SYS:PSP_CLEARING` (ASSET): debit on card capture → balance rises (gateway is owed by PSP).
- `SYS:FEE_REVENUE` (REVENUE): credit on capture → balance rises.

---

## 5. Posting Rules

Only CAPTURE and REFUND post entries. AUTHORIZE and VOID involve no actual money movement and produce no ledger postings.

Let `A` = capture/refund amount, `F` = fee (0 if no fee).

### Wallet Capture
```
DR  WALLET:{walletId}      A
CR  MERCHANT:{merchantId}  A − F
CR  SYS:FEE_REVENUE        F      (omitted if F == 0)
```

### Card Capture (PSP-mediated)
```
DR  SYS:PSP_CLEARING       A
CR  MERCHANT:{merchantId}  A − F
CR  SYS:FEE_REVENUE        F      (omitted if F == 0)
```

### Wallet Refund (amount `R`)
```
DR  MERCHANT:{merchantId}  R
CR  WALLET:{walletId}      R
```

### Card Refund (amount `R`)
```
DR  MERCHANT:{merchantId}  R
CR  SYS:PSP_CLEARING       R
```

**Fee policy on refund:** Fee revenue is NOT reversed. The gateway retains its fee. This is an explicit policy decision for this slice.

**Fee lookup:** `TransactionFee` rows for the transaction (if any) are summed to determine `F`. If none, `F = 0`.

---

## 6. Concurrency & Atomicity

`LedgerService.post(...)` must run **inside the same DB transaction** as the payment operation (capture or refund). This ensures atomicity: if the payment fails, no ledger entry is written; if the ledger write fails, the payment rolls back.

Concurrency safety:
1. Affected `LedgerAccount` rows are locked with `SELECT ... FOR UPDATE` **ordered by account id** (prevents deadlock from lock ordering).
2. For the wallet path, the Redisson distributed lock is already held by `processWalletDebit`; the pessimistic row lock on `LedgerAccount` is the DB-layer absolute guarantee.
3. The invariant check (Σdebits == Σcredits) runs before any write; if it fails, the method throws `IllegalStateException` and the transaction rolls back.

---

## 7. Module Placement

```
payment-domain
  └── entity/
        LedgerAccount.java        (entity, classification + owner enums)
        JournalEntry.java         (entity)
        JournalLine.java          (entity)

payment-core
  └── ledger/
        LedgerService.java        (post, findOrCreateAccount, trialBalance)
        LedgerPostingRequest.java (record: entry metadata + lines)
        LedgerLineRequest.java    (record: accountCode, currency, direction, amount)
        AccountClassification.java (enum: ASSET, LIABILITY, REVENUE)
        AccountOwnerType.java      (enum: USER_WALLET, MERCHANT, SYSTEM)
        EntryDirection.java        (enum: DEBIT, CREDIT)
  └── repository/
        LedgerAccountRepository.java
        JournalEntryRepository.java
        JournalLineRepository.java

payment-api
  └── db/migration/
        V5__add_ledger.sql        (tables + indexes + system-account seed rows)
```

`PaymentService` calls `ledgerService.post(...)` at two points:
- After `transaction.capture()` in `capturePayment`.
- After `refundRepository.save(refund)` in `refundPayment`.

Both are already `@Transactional` methods, so the ledger write participates in the same transaction.

---

## 8. API / Query Surface

No new REST endpoints in this slice. The ledger is internal. Two service methods exposed for future use:

```java
// Core posting — called by PaymentService
void post(LedgerPostingRequest request);

// Sum of all DEBIT line amounts vs CREDIT line amounts globally.
// Returns true if balanced; used by tests and a future reconciliation job.
boolean trialBalance();
```

---

## 9. Testing Strategy

### Unit (payment-core, no Spring context)
- `LedgerService` rejects: unbalanced entry, negative/zero amount, mixed currency, <2 lines.
- Correct balance delta per classification (DR ASSET +, DR LIABILITY −, CR REVENUE +, etc.).
- Reversing entry math cancels to zero.

### Integration (payment-api, H2)
- Wallet capture: `LedgerAccount` balances move correctly; `JournalLine` rows are appended; `trialBalance()` returns true.
- Card capture: PSP_CLEARING and MERCHANT accounts move correctly.
- Partial refund: MERCHANT balance decreases, payer account balance increases; fee-revenue untouched.
- Trial balance holds across a sequence of captures + refunds.
- **Concurrency:** parallel captures on the same transaction do not produce a double-post or corrupted balance (uses the existing pessimistic-lock pattern).
- **Atomicity:** a forced failure after the capture but before ledger write rolls back both (test with a spy).

---

## 10. Migration (V5)

`V5__add_ledger.sql`:
- Create `ledger_accounts` with unique constraint on `(code, currency)`.
- Create `journal_entries` with index on `transaction_id`.
- Create `journal_lines` with index on `journal_entry_id` and `account_id`.
- Seed system accounts for each supported currency (initially `USD`): `SYS:PSP_CLEARING`, `SYS:FEE_REVENUE`, `SYS:GATEWAY_CASH`.

---

## 11. Decision Summary

| Decision | Choice | Rationale |
|---|---|---|
| Ledger role | Parallel system of record | Additive; doesn't break existing wallet tests |
| Account model | Per-owner + system accounts | Can answer per-merchant balance from ledger |
| Balance storage | Materialized + immutable lines | Fast reads; matches existing concurrency model |
| Posting points | CAPTURE and REFUND only | Only these move money |
| Fee on refund | Not reversed (gateway keeps fee) | Explicit simple policy; deferrable |
| Balance orientation | Natural-side delta formula | Intuitive display per account type |
