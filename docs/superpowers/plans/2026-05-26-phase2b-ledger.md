# Phase 2b — Double-Entry Ledger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a parallel double-entry ledger that posts a balanced journal entry on every capture and refund, with materialized account balances guarded by pessimistic DB locks.

**Architecture:** Three new `payment-domain` entities (`LedgerAccount`, `JournalEntry`, `JournalLine`) and a `LedgerService` in `payment-core`. `PaymentService` calls `ledgerService.post(...)` inside its existing `@Transactional` methods so the ledger write and the money-movement are atomic. Account balances are materialized (running total), locked in id-sorted order to prevent deadlock. Existing `WalletTransaction` rows are untouched.

**Tech Stack:** Java 17, Spring Boot 3.5.x, Maven multi-module, PostgreSQL + Flyway (V5), JPA/Hibernate with `@Lock(PESSIMISTIC_WRITE)`, JUnit 5 + AssertJ + Mockito, H2 (PostgreSQL mode) for tests.

---

## File map

| Action | Path | Purpose |
|---|---|---|
| Create | `payment-domain/.../entity/AccountClassification.java` | Enum: ASSET, LIABILITY, REVENUE |
| Create | `payment-domain/.../entity/AccountOwnerType.java` | Enum: USER_WALLET, MERCHANT, SYSTEM |
| Create | `payment-domain/.../entity/EntryDirection.java` | Enum: DEBIT, CREDIT |
| Create | `payment-domain/.../entity/EntryEventType.java` | Enum: CAPTURE, REFUND |
| Create | `payment-domain/.../entity/LedgerAccount.java` | Entity: materialized balance per owner/currency |
| Create | `payment-domain/.../entity/JournalEntry.java` | Entity: journal entry header |
| Create | `payment-domain/.../entity/JournalLine.java` | Entity: one debit or credit line |
| Create | `payment-core/.../ledger/LedgerLineRequest.java` | Record: one line of a posting request |
| Create | `payment-core/.../ledger/LedgerPostingRequest.java` | Record: full posting request |
| Create | `payment-core/.../repository/LedgerAccountRepository.java` | JPA repo with pessimistic-lock finder |
| Create | `payment-core/.../repository/JournalEntryRepository.java` | JPA repo |
| Create | `payment-core/.../repository/JournalLineRepository.java` | JPA repo with sum-by-direction query |
| Create | `payment-core/.../repository/TransactionFeeRepository.java` | JPA repo for existing TransactionFee entity |
| Create | `payment-core/.../ledger/LedgerService.java` | Service: validate, resolve accounts, post, trialBalance |
| Create | `payment-api/.../db/migration/V5__add_ledger.sql` | Tables, indexes, system account seed rows |
| Modify | `payment-core/.../service/PaymentService.java` | Add LedgerService field; call post on capture + refund |
| Create | `payment-core/.../ledger/LedgerServiceTest.java` | Unit tests: validation + balance math |
| Create | `payment-api/.../LedgerIntegrationTest.java` | Integration: capture/refund posts, trial balance |

---

## Task 1: Domain enums and entities

**Files:**
- Create: `payment-domain/src/main/java/com/kimpay/payment/domain/entity/AccountClassification.java`
- Create: `payment-domain/src/main/java/com/kimpay/payment/domain/entity/AccountOwnerType.java`
- Create: `payment-domain/src/main/java/com/kimpay/payment/domain/entity/EntryDirection.java`
- Create: `payment-domain/src/main/java/com/kimpay/payment/domain/entity/EntryEventType.java`
- Create: `payment-domain/src/main/java/com/kimpay/payment/domain/entity/LedgerAccount.java`
- Create: `payment-domain/src/main/java/com/kimpay/payment/domain/entity/JournalEntry.java`
- Create: `payment-domain/src/main/java/com/kimpay/payment/domain/entity/JournalLine.java`

- [ ] **Step 1: Create the four enums**

`AccountClassification.java`:
```java
package com.kimpay.payment.domain.entity;

public enum AccountClassification {
    ASSET, LIABILITY, REVENUE
}
```

`AccountOwnerType.java`:
```java
package com.kimpay.payment.domain.entity;

public enum AccountOwnerType {
    USER_WALLET, MERCHANT, SYSTEM
}
```

`EntryDirection.java`:
```java
package com.kimpay.payment.domain.entity;

public enum EntryDirection {
    DEBIT, CREDIT
}
```

`EntryEventType.java`:
```java
package com.kimpay.payment.domain.entity;

public enum EntryEventType {
    CAPTURE, REFUND
}
```

- [ ] **Step 2: Create `LedgerAccount`**

```java
package com.kimpay.payment.domain.entity;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(
    name = "ledger_accounts",
    uniqueConstraints = @UniqueConstraint(columnNames = {"code", "currency"})
)
@Getter
@Setter
@EqualsAndHashCode(of = "id", callSuper = false)
public class LedgerAccount extends AbstractCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String code;

    @Column(name = "owner_type", nullable = false, length = 30)
    private String ownerType;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(nullable = false, length = 20)
    private String classification;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;
}
```

- [ ] **Step 3: Create `JournalEntry`**

```java
package com.kimpay.payment.domain.entity;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "journal_entries")
@Getter
@Setter
@EqualsAndHashCode(of = "id", callSuper = false)
public class JournalEntry extends AbstractCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Column(name = "event_type", nullable = false, length = 20)
    private String eventType;

    @Column(length = 255)
    private String description;
}
```

- [ ] **Step 4: Create `JournalLine`**

```java
package com.kimpay.payment.domain.entity;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "journal_lines")
@Getter
@Setter
@EqualsAndHashCode(of = "id", callSuper = false)
public class JournalLine extends AbstractCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "journal_entry_id", nullable = false)
    private Long journalEntryId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false, length = 6)
    private String direction;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;
}
```

- [ ] **Step 5: Compile payment-domain**

Run: `./mvnw -pl payment-domain compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add payment-domain/src/main/java/com/kimpay/payment/domain/entity/AccountClassification.java payment-domain/src/main/java/com/kimpay/payment/domain/entity/AccountOwnerType.java payment-domain/src/main/java/com/kimpay/payment/domain/entity/EntryDirection.java payment-domain/src/main/java/com/kimpay/payment/domain/entity/EntryEventType.java payment-domain/src/main/java/com/kimpay/payment/domain/entity/LedgerAccount.java payment-domain/src/main/java/com/kimpay/payment/domain/entity/JournalEntry.java payment-domain/src/main/java/com/kimpay/payment/domain/entity/JournalLine.java
git commit -m "feat(ledger): add LedgerAccount, JournalEntry, JournalLine entities and enums"
```

---

## Task 2: Core value types and repositories

**Files:**
- Create: `payment-core/src/main/java/com/kimpay/payment/core/ledger/LedgerLineRequest.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/ledger/LedgerPostingRequest.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/repository/LedgerAccountRepository.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/repository/JournalEntryRepository.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/repository/JournalLineRepository.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/repository/TransactionFeeRepository.java`

- [ ] **Step 1: Create `LedgerLineRequest`**

```java
package com.kimpay.payment.core.ledger;

import com.kimpay.payment.domain.entity.EntryDirection;

import java.math.BigDecimal;

/**
 * One debit or credit line in a posting request.
 *
 * @param accountCode  e.g. "WALLET:7", "MERCHANT:2", "SYS:FEE_REVENUE"
 * @param currency     ISO 4217 uppercase
 * @param direction    DEBIT or CREDIT
 * @param amount       must be > 0
 */
public record LedgerLineRequest(
        String accountCode,
        String currency,
        EntryDirection direction,
        BigDecimal amount
) {}
```

- [ ] **Step 2: Create `LedgerPostingRequest`**

```java
package com.kimpay.payment.core.ledger;

import com.kimpay.payment.domain.entity.EntryEventType;

import java.util.List;

/**
 * A complete, atomic posting request. All lines must balance (Σdebits == Σcredits),
 * share a single currency, and contain ≥ 2 lines with amounts > 0.
 */
public record LedgerPostingRequest(
        Long transactionId,
        EntryEventType eventType,
        String description,
        List<LedgerLineRequest> lines
) {}
```

- [ ] **Step 3: Create `LedgerAccountRepository`**

```java
package com.kimpay.payment.core.repository;

import com.kimpay.payment.domain.entity.LedgerAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, Long> {

    Optional<LedgerAccount> findByCodeAndCurrency(String code, String currency);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM LedgerAccount a WHERE a.id = :id")
    Optional<LedgerAccount> findWithLockById(@Param("id") Long id);
}
```

- [ ] **Step 4: Create `JournalEntryRepository`**

```java
package com.kimpay.payment.core.repository;

import com.kimpay.payment.domain.entity.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {
    List<JournalEntry> findAllByTransactionId(Long transactionId);
}
```

- [ ] **Step 5: Create `JournalLineRepository`**

```java
package com.kimpay.payment.core.repository;

import com.kimpay.payment.domain.entity.JournalLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface JournalLineRepository extends JpaRepository<JournalLine, Long> {

    List<JournalLine> findAllByJournalEntryId(Long journalEntryId);

    @Query("SELECT COALESCE(SUM(l.amount), 0) FROM JournalLine l WHERE l.direction = :direction")
    BigDecimal sumByDirection(@Param("direction") String direction);
}
```

- [ ] **Step 6: Create `TransactionFeeRepository`**

```java
package com.kimpay.payment.core.repository;

import com.kimpay.payment.domain.entity.TransactionFee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionFeeRepository extends JpaRepository<TransactionFee, Long> {
    List<TransactionFee> findAllByTransactionId(Long transactionId);
}
```

- [ ] **Step 7: Compile payment-core**

Run: `./mvnw -pl payment-core -am compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add payment-core/src/main/java/com/kimpay/payment/core/ledger/ payment-core/src/main/java/com/kimpay/payment/core/repository/LedgerAccountRepository.java payment-core/src/main/java/com/kimpay/payment/core/repository/JournalEntryRepository.java payment-core/src/main/java/com/kimpay/payment/core/repository/JournalLineRepository.java payment-core/src/main/java/com/kimpay/payment/core/repository/TransactionFeeRepository.java
git commit -m "feat(ledger): add ledger value types and repositories"
```

---

## Task 3: `LedgerService` — validation and balance math (TDD)

**Files:**
- Create: `payment-core/src/test/java/com/kimpay/payment/core/ledger/LedgerServiceTest.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/ledger/LedgerService.java`

- [ ] **Step 1: Write the failing tests**

`LedgerServiceTest.java`:
```java
package com.kimpay.payment.core.ledger;

import com.kimpay.payment.core.repository.JournalEntryRepository;
import com.kimpay.payment.core.repository.JournalLineRepository;
import com.kimpay.payment.core.repository.LedgerAccountRepository;
import com.kimpay.payment.core.repository.TransactionFeeRepository;
import com.kimpay.payment.domain.entity.AccountClassification;
import com.kimpay.payment.domain.entity.EntryDirection;
import com.kimpay.payment.domain.entity.EntryEventType;
import com.kimpay.payment.domain.entity.LedgerAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LedgerServiceTest {

    private LedgerAccountRepository accountRepo;
    private JournalEntryRepository entryRepo;
    private JournalLineRepository lineRepo;
    private LedgerService service;

    @BeforeEach
    void setUp() {
        accountRepo = mock(LedgerAccountRepository.class);
        entryRepo = mock(JournalEntryRepository.class);
        lineRepo = mock(JournalLineRepository.class);
        service = new LedgerService(accountRepo, entryRepo, lineRepo);

        // Default: accounts exist and are returned by lock-finder
        LedgerAccount wallet = makeAccount(1L, "WALLET:7", "USD", AccountClassification.LIABILITY, BigDecimal.ZERO);
        LedgerAccount merchant = makeAccount(2L, "MERCHANT:2", "USD", AccountClassification.LIABILITY, BigDecimal.ZERO);
        when(accountRepo.findByCodeAndCurrency("WALLET:7", "USD")).thenReturn(Optional.of(wallet));
        when(accountRepo.findByCodeAndCurrency("MERCHANT:2", "USD")).thenReturn(Optional.of(merchant));
        when(accountRepo.findWithLockById(1L)).thenReturn(Optional.of(wallet));
        when(accountRepo.findWithLockById(2L)).thenReturn(Optional.of(merchant));
        when(accountRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var savedEntry = new com.kimpay.payment.domain.entity.JournalEntry();
        savedEntry.setId(99L);
        savedEntry.setTransactionId(42L);
        savedEntry.setEventType("CAPTURE");
        when(entryRepo.save(any())).thenReturn(savedEntry);
        when(lineRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Test
    void rejectsFewerthanTwoLines() {
        LedgerPostingRequest req = new LedgerPostingRequest(42L, EntryEventType.CAPTURE, "test",
                List.of(new LedgerLineRequest("WALLET:7", "USD", EntryDirection.DEBIT, new BigDecimal("10.00"))));
        assertThatThrownBy(() -> service.post(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 lines");
    }

    @Test
    void rejectsUnbalancedEntry() {
        LedgerPostingRequest req = new LedgerPostingRequest(42L, EntryEventType.CAPTURE, "test", List.of(
                new LedgerLineRequest("WALLET:7", "USD", EntryDirection.DEBIT, new BigDecimal("10.00")),
                new LedgerLineRequest("MERCHANT:2", "USD", EntryDirection.CREDIT, new BigDecimal("9.00"))
        ));
        assertThatThrownBy(() -> service.post(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("balanced");
    }

    @Test
    void rejectsZeroAmount() {
        LedgerPostingRequest req = new LedgerPostingRequest(42L, EntryEventType.CAPTURE, "test", List.of(
                new LedgerLineRequest("WALLET:7", "USD", EntryDirection.DEBIT, BigDecimal.ZERO),
                new LedgerLineRequest("MERCHANT:2", "USD", EntryDirection.CREDIT, BigDecimal.ZERO)
        ));
        assertThatThrownBy(() -> service.post(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void rejectsMixedCurrency() {
        LedgerPostingRequest req = new LedgerPostingRequest(42L, EntryEventType.CAPTURE, "test", List.of(
                new LedgerLineRequest("WALLET:7", "USD", EntryDirection.DEBIT, new BigDecimal("10.00")),
                new LedgerLineRequest("MERCHANT:2", "EUR", EntryDirection.CREDIT, new BigDecimal("10.00"))
        ));
        assertThatThrownBy(() -> service.post(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }

    // ── Balance delta math ────────────────────────────────────────────────────

    @Test
    void debitOnLiabilityAccountDecreasesBalance() {
        // WALLET:7 is LIABILITY; DR 10 should reduce balance from 50 → 40
        LedgerAccount wallet = makeAccount(1L, "WALLET:7", "USD", AccountClassification.LIABILITY, new BigDecimal("50.00"));
        when(accountRepo.findByCodeAndCurrency("WALLET:7", "USD")).thenReturn(Optional.of(wallet));
        when(accountRepo.findWithLockById(1L)).thenReturn(Optional.of(wallet));
        LedgerAccount merchant = makeAccount(2L, "MERCHANT:2", "USD", AccountClassification.LIABILITY, BigDecimal.ZERO);
        when(accountRepo.findByCodeAndCurrency("MERCHANT:2", "USD")).thenReturn(Optional.of(merchant));
        when(accountRepo.findWithLockById(2L)).thenReturn(Optional.of(merchant));

        service.post(new LedgerPostingRequest(42L, EntryEventType.CAPTURE, "test", List.of(
                new LedgerLineRequest("WALLET:7", "USD", EntryDirection.DEBIT, new BigDecimal("10.00")),
                new LedgerLineRequest("MERCHANT:2", "USD", EntryDirection.CREDIT, new BigDecimal("10.00"))
        )));

        assertThat(wallet.getBalance()).isEqualByComparingTo("40.00");
    }

    @Test
    void creditOnLiabilityAccountIncreasesBalance() {
        // MERCHANT:2 is LIABILITY; CR 10 should increase balance from 0 → 10
        LedgerAccount wallet = makeAccount(1L, "WALLET:7", "USD", AccountClassification.LIABILITY, new BigDecimal("50.00"));
        when(accountRepo.findByCodeAndCurrency("WALLET:7", "USD")).thenReturn(Optional.of(wallet));
        when(accountRepo.findWithLockById(1L)).thenReturn(Optional.of(wallet));
        LedgerAccount merchant = makeAccount(2L, "MERCHANT:2", "USD", AccountClassification.LIABILITY, BigDecimal.ZERO);
        when(accountRepo.findByCodeAndCurrency("MERCHANT:2", "USD")).thenReturn(Optional.of(merchant));
        when(accountRepo.findWithLockById(2L)).thenReturn(Optional.of(merchant));

        service.post(new LedgerPostingRequest(42L, EntryEventType.CAPTURE, "test", List.of(
                new LedgerLineRequest("WALLET:7", "USD", EntryDirection.DEBIT, new BigDecimal("10.00")),
                new LedgerLineRequest("MERCHANT:2", "USD", EntryDirection.CREDIT, new BigDecimal("10.00"))
        )));

        assertThat(merchant.getBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    void debitOnAssetAccountIncreasesBalance() {
        // SYS:PSP_CLEARING is ASSET; DR 10 should raise balance 0 → 10
        LedgerAccount psp = makeAccount(3L, "SYS:PSP_CLEARING", "USD", AccountClassification.ASSET, BigDecimal.ZERO);
        LedgerAccount merchant = makeAccount(2L, "MERCHANT:2", "USD", AccountClassification.LIABILITY, BigDecimal.ZERO);
        when(accountRepo.findByCodeAndCurrency("SYS:PSP_CLEARING", "USD")).thenReturn(Optional.of(psp));
        when(accountRepo.findByCodeAndCurrency("MERCHANT:2", "USD")).thenReturn(Optional.of(merchant));
        when(accountRepo.findWithLockById(2L)).thenReturn(Optional.of(merchant));
        when(accountRepo.findWithLockById(3L)).thenReturn(Optional.of(psp));

        service.post(new LedgerPostingRequest(42L, EntryEventType.CAPTURE, "test", List.of(
                new LedgerLineRequest("SYS:PSP_CLEARING", "USD", EntryDirection.DEBIT, new BigDecimal("10.00")),
                new LedgerLineRequest("MERCHANT:2", "USD", EntryDirection.CREDIT, new BigDecimal("10.00"))
        )));

        assertThat(psp.getBalance()).isEqualByComparingTo("10.00");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private LedgerAccount makeAccount(Long id, String code, String currency,
                                       AccountClassification cls, BigDecimal balance) {
        LedgerAccount acc = new LedgerAccount();
        acc.setId(id);
        acc.setCode(code);
        acc.setCurrency(currency);
        acc.setClassification(cls.name());
        acc.setBalance(balance);
        return acc;
    }
}
```

- [ ] **Step 2: Run to verify compilation fails (LedgerService doesn't exist)**

Run: `./mvnw -pl payment-core -am test -Dtest=LedgerServiceTest -q`
Expected: COMPILE FAILURE — `LedgerService` not found.

- [ ] **Step 3: Implement `LedgerService`**

`LedgerService.java`:
```java
package com.kimpay.payment.core.ledger;

import com.kimpay.payment.core.repository.JournalEntryRepository;
import com.kimpay.payment.core.repository.JournalLineRepository;
import com.kimpay.payment.core.repository.LedgerAccountRepository;
import com.kimpay.payment.domain.entity.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final LedgerAccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalLineRepository journalLineRepository;

    /**
     * Post a balanced journal entry atomically. Must be called inside an existing @Transactional
     * so the ledger write and the surrounding money-movement share one DB transaction.
     */
    @Transactional
    public void post(LedgerPostingRequest request) {
        validate(request);

        // Resolve all needed accounts (find or create lazily for owner accounts)
        Map<String, LedgerAccount> accountsByKey = new LinkedHashMap<>();
        for (LedgerLineRequest line : request.lines()) {
            String key = line.accountCode() + ":" + line.currency();
            if (!accountsByKey.containsKey(key)) {
                accountsByKey.put(key, findOrCreate(line.accountCode(), line.currency()));
            }
        }

        // Lock all accounts ordered by id to prevent deadlock
        List<Long> sortedIds = accountsByKey.values().stream()
                .map(LedgerAccount::getId)
                .sorted()
                .collect(Collectors.toList());
        Map<Long, LedgerAccount> lockedById = new LinkedHashMap<>();
        for (Long id : sortedIds) {
            lockedById.put(id, accountRepository.findWithLockById(id)
                    .orElseThrow(() -> new IllegalStateException("Ledger account disappeared: " + id)));
        }
        // Remap by key using locked instances
        Map<String, LedgerAccount> locked = new LinkedHashMap<>();
        for (Map.Entry<String, LedgerAccount> e : accountsByKey.entrySet()) {
            locked.put(e.getKey(), lockedById.get(e.getValue().getId()));
        }

        // Persist entry header
        JournalEntry entry = new JournalEntry();
        entry.setTransactionId(request.transactionId());
        entry.setEventType(request.eventType().name());
        entry.setDescription(request.description());
        entry = journalEntryRepository.save(entry);

        // Persist lines and apply balance deltas
        for (LedgerLineRequest lineReq : request.lines()) {
            String key = lineReq.accountCode() + ":" + lineReq.currency();
            LedgerAccount account = locked.get(key);

            JournalLine line = new JournalLine();
            line.setJournalEntryId(entry.getId());
            line.setAccountId(account.getId());
            line.setDirection(lineReq.direction().name());
            line.setAmount(lineReq.amount());
            line.setCurrency(lineReq.currency());
            journalLineRepository.save(line);

            applyDelta(account, lineReq.direction(), lineReq.amount());
            accountRepository.save(account);
        }

        log.debug("[ledger] posted {} entry for txn {}: {} lines",
                request.eventType(), request.transactionId(), request.lines().size());
    }

    /**
     * Returns true if the sum of all DEBIT line amounts equals the sum of all CREDIT line amounts.
     * Used for trial-balance assertions in tests and future reconciliation.
     */
    @Transactional(readOnly = true)
    public boolean trialBalance() {
        BigDecimal debits = journalLineRepository.sumByDirection("DEBIT");
        BigDecimal credits = journalLineRepository.sumByDirection("CREDIT");
        if (debits == null) debits = BigDecimal.ZERO;
        if (credits == null) credits = BigDecimal.ZERO;
        return debits.compareTo(credits) == 0;
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private void validate(LedgerPostingRequest request) {
        List<LedgerLineRequest> lines = request.lines();
        if (lines == null || lines.size() < 2) {
            throw new IllegalArgumentException("Journal entry requires at least 2 lines");
        }
        long distinctCurrencies = lines.stream().map(LedgerLineRequest::currency).distinct().count();
        if (distinctCurrencies != 1) {
            throw new IllegalArgumentException("All lines in a journal entry must share a single currency");
        }
        for (LedgerLineRequest line : lines) {
            if (line.amount() == null || line.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Journal line amount must be > 0");
            }
        }
        BigDecimal debits = lines.stream()
                .filter(l -> l.direction() == EntryDirection.DEBIT)
                .map(LedgerLineRequest::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = lines.stream()
                .filter(l -> l.direction() == EntryDirection.CREDIT)
                .map(LedgerLineRequest::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (debits.compareTo(credits) != 0) {
            throw new IllegalArgumentException(
                    "Journal entry is not balanced: debits=" + debits + " credits=" + credits);
        }
    }

    private LedgerAccount findOrCreate(String code, String currency) {
        return accountRepository.findByCodeAndCurrency(code, currency)
                .orElseGet(() -> createAccount(code, currency));
    }

    private LedgerAccount createAccount(String code, String currency) {
        LedgerAccount acc = new LedgerAccount();
        acc.setCode(code);
        acc.setCurrency(currency);
        acc.setBalance(BigDecimal.ZERO);
        if (code.startsWith("WALLET:")) {
            acc.setOwnerType(AccountOwnerType.USER_WALLET.name());
            acc.setOwnerId(Long.parseLong(code.substring(7)));
            acc.setClassification(AccountClassification.LIABILITY.name());
        } else if (code.startsWith("MERCHANT:")) {
            acc.setOwnerType(AccountOwnerType.MERCHANT.name());
            acc.setOwnerId(Long.parseLong(code.substring(9)));
            acc.setClassification(AccountClassification.LIABILITY.name());
        } else {
            acc.setOwnerType(AccountOwnerType.SYSTEM.name());
            acc.setClassification(systemClassification(code).name());
        }
        return accountRepository.save(acc);
    }

    private AccountClassification systemClassification(String code) {
        return switch (code) {
            case "SYS:PSP_CLEARING", "SYS:GATEWAY_CASH" -> AccountClassification.ASSET;
            case "SYS:FEE_REVENUE" -> AccountClassification.REVENUE;
            default -> throw new IllegalArgumentException("Unknown system account: " + code);
        };
    }

    /**
     * Balance delta = (DEBIT?+1:-1) * (ASSET?+1:-1) * amount.
     * ASSET is debit-normal: DR increases, CR decreases.
     * LIABILITY/REVENUE are credit-normal: CR increases, DR decreases.
     */
    private void applyDelta(LedgerAccount account, EntryDirection direction, BigDecimal amount) {
        int debitSign = direction == EntryDirection.DEBIT ? 1 : -1;
        int classSign = AccountClassification.ASSET.name().equals(account.getClassification()) ? 1 : -1;
        BigDecimal delta = amount.multiply(BigDecimal.valueOf((long) debitSign * classSign));
        account.setBalance(account.getBalance().add(delta));
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -pl payment-core -am test -Dtest=LedgerServiceTest -q`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add payment-core/src/main/java/com/kimpay/payment/core/ledger/LedgerService.java payment-core/src/test/java/com/kimpay/payment/core/ledger/LedgerServiceTest.java
git commit -m "feat(ledger): LedgerService with validation and balance math"
```

---

## Task 4: Flyway V5 — ledger schema migration

**Files:**
- Create: `payment-api/src/main/resources/db/migration/V5__add_ledger.sql`

- [ ] **Step 1: Write the migration**

`V5__add_ledger.sql`:
```sql
-- Double-entry ledger: accounts, journal entries, and journal lines.

CREATE TABLE IF NOT EXISTS ledger_accounts (
    id             BIGSERIAL PRIMARY KEY,
    code           VARCHAR(80)    NOT NULL,
    owner_type     VARCHAR(30)    NOT NULL,
    owner_id       BIGINT,
    classification VARCHAR(20)    NOT NULL,
    currency       CHAR(3)        NOT NULL,
    balance        NUMERIC(18,2)  NOT NULL DEFAULT 0.00,
    created_at     TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ledger_account_code_currency UNIQUE (code, currency)
);

CREATE INDEX IF NOT EXISTS idx_ledger_accounts_owner
    ON ledger_accounts (owner_type, owner_id);

CREATE TABLE IF NOT EXISTS journal_entries (
    id             BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT         NOT NULL REFERENCES transactions(id),
    event_type     VARCHAR(20)    NOT NULL,
    description    VARCHAR(255),
    created_at     TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_journal_entries_transaction_id
    ON journal_entries (transaction_id);

CREATE TABLE IF NOT EXISTS journal_lines (
    id               BIGSERIAL PRIMARY KEY,
    journal_entry_id BIGINT         NOT NULL REFERENCES journal_entries(id),
    account_id       BIGINT         NOT NULL REFERENCES ledger_accounts(id),
    direction        VARCHAR(6)     NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount           NUMERIC(18,2)  NOT NULL CHECK (amount > 0),
    currency         CHAR(3)        NOT NULL,
    created_at       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_journal_lines_entry
    ON journal_lines (journal_entry_id);
CREATE INDEX IF NOT EXISTS idx_journal_lines_account
    ON journal_lines (account_id);

-- Seed system accounts for USD (add more currencies here when needed).
INSERT INTO ledger_accounts (code, owner_type, classification, currency, balance)
VALUES
    ('SYS:PSP_CLEARING',  'SYSTEM', 'ASSET',   'USD', 0.00),
    ('SYS:FEE_REVENUE',   'SYSTEM', 'REVENUE',  'USD', 0.00),
    ('SYS:GATEWAY_CASH',  'SYSTEM', 'ASSET',   'USD', 0.00)
ON CONFLICT (code, currency) DO NOTHING;
```

> **H2 note:** Tests use `ddl-auto: create-drop` with Flyway disabled, so H2 auto-creates tables from entities. The `CHECK` constraints are PostgreSQL-only and silently ignored by H2. The `ON CONFLICT DO NOTHING` syntax is supported by H2 in PostgreSQL mode. No test changes needed.

- [ ] **Step 2: Compile payment-api (validates migration syntax)**

Run: `./mvnw -pl payment-api -am compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add payment-api/src/main/resources/db/migration/V5__add_ledger.sql
git commit -m "feat(ledger): Flyway V5 — ledger_accounts, journal_entries, journal_lines tables"
```

---

## Task 5: Wire `LedgerService` into `PaymentService` (TDD)

**Files:**
- Modify: `payment-core/src/main/java/com/kimpay/payment/core/service/PaymentService.java`
- Modify: `payment-core/src/test/java/com/kimpay/payment/core/service/PaymentServicePspTest.java` (add ledger mock)
- Create: `payment-api/src/test/java/com/kimpay/payment/LedgerIntegrationTest.java`

### 5a — Integration tests first (red)

- [ ] **Step 1: Write `LedgerIntegrationTest`**

`LedgerIntegrationTest.java`:
```java
package com.kimpay.payment;

import com.kimpay.payment.core.dto.CreatePaymentRequest;
import com.kimpay.payment.core.dto.PaymentResponse;
import com.kimpay.payment.core.dto.RefundPaymentRequest;
import com.kimpay.payment.core.ledger.LedgerService;
import com.kimpay.payment.core.repository.*;
import com.kimpay.payment.core.service.PaymentService;
import com.kimpay.payment.domain.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "payment.encryption.key-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="
})
class LedgerIntegrationTest {

    @Autowired PaymentService paymentService;
    @Autowired LedgerService ledgerService;
    @Autowired UserRepository userRepository;
    @Autowired MerchantRepository merchantRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired WalletTransactionRepository walletTransactionRepository;
    @Autowired RefundRepository refundRepository;
    @Autowired LedgerAccountRepository ledgerAccountRepository;
    @Autowired JournalEntryRepository journalEntryRepository;
    @Autowired JournalLineRepository journalLineRepository;

    Long userId, merchantId, walletId;

    @BeforeEach
    void setUp() {
        journalLineRepository.deleteAll();
        journalEntryRepository.deleteAll();
        // don't delete ledger_accounts seeded for system; delete only owner accounts
        ledgerAccountRepository.findAll().stream()
                .filter(a -> !"SYSTEM".equals(a.getOwnerType()))
                .forEach(ledgerAccountRepository::delete);

        refundRepository.deleteAll();
        walletTransactionRepository.deleteAll();
        transactionRepository.deleteAll();
        walletRepository.deleteAll();
        merchantRepository.deleteAll();
        userRepository.deleteAll();

        User u = new User(); u.setName("U"); u.setEmail("u@test.com");
        u.setPasswordHash("h"); u.setRoleId(1L);
        userId = userRepository.save(u).getId();
        Merchant m = new Merchant(); m.setUserId(userId);
        m.setBusinessName("M"); m.setStatus("active");
        merchantId = merchantRepository.save(m).getId();
        Wallet w = new Wallet(); w.setUserId(userId);
        w.setCurrency("USD"); w.setBalance(new BigDecimal("100.00"));
        walletId = walletRepository.save(w).getId();
    }

    @Test
    void walletCapturePostsBalancedJournalEntry() {
        CreatePaymentRequest req = new CreatePaymentRequest(
                userId, merchantId, null, walletId, new BigDecimal("30.00"), "USD", true, null);

        paymentService.createPayment(req);

        // One journal entry posted
        var entries = journalEntryRepository.findAll();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getEventType()).isEqualTo("CAPTURE");

        // Two lines (no fee configured): DR WALLET / CR MERCHANT
        var lines = journalLineRepository.findAll();
        assertThat(lines).hasSize(2);

        // Trial balance holds
        assertThat(ledgerService.trialBalance()).isTrue();

        // WALLET account balance reduced by 30 (LIABILITY DR)
        Optional<LedgerAccount> walletAcc = ledgerAccountRepository
                .findByCodeAndCurrency("WALLET:" + walletId, "USD");
        assertThat(walletAcc).isPresent();
        assertThat(walletAcc.get().getBalance()).isEqualByComparingTo("30.00");

        // MERCHANT account balance increased by 30 (LIABILITY CR)
        Optional<LedgerAccount> merchantAcc = ledgerAccountRepository
                .findByCodeAndCurrency("MERCHANT:" + merchantId, "USD");
        assertThat(merchantAcc).isPresent();
        assertThat(merchantAcc.get().getBalance()).isEqualByComparingTo("30.00");
    }

    @Test
    void refundReversesBalancesAndTrialBalanceHolds() {
        CreatePaymentRequest captureReq = new CreatePaymentRequest(
                userId, merchantId, null, walletId, new BigDecimal("30.00"), "USD", true, null);
        PaymentResponse captured = paymentService.createPayment(captureReq);

        paymentService.refundPayment(captured.id(),
                new RefundPaymentRequest(new BigDecimal("10.00"), "test refund"));

        assertThat(ledgerService.trialBalance()).isTrue();

        // Two entries: one CAPTURE, one REFUND
        assertThat(journalEntryRepository.findAll()).hasSize(2);

        // MERCHANT account balance: 30 - 10 = 20
        LedgerAccount merchantAcc = ledgerAccountRepository
                .findByCodeAndCurrency("MERCHANT:" + merchantId, "USD").orElseThrow();
        assertThat(merchantAcc.getBalance()).isEqualByComparingTo("20.00");

        // WALLET account balance: 30 + 10 = ... wait, wallet balance is DR-applied on capture,
        // CR-applied on refund: LIABILITY DR=−, CR=+. So post-capture=30, post-refund=30−10=20.
        LedgerAccount walletAcc = ledgerAccountRepository
                .findByCodeAndCurrency("WALLET:" + walletId, "USD").orElseThrow();
        assertThat(walletAcc.getBalance()).isEqualByComparingTo("20.00");
    }

    @Test
    void trialBalanceHoldsAcrossMultipleCaptures() {
        for (int i = 1; i <= 3; i++) {
            paymentService.createPayment(new CreatePaymentRequest(
                    userId, merchantId, null, walletId,
                    new BigDecimal("10.00"), "USD", true, "key-" + i));
        }
        assertThat(ledgerService.trialBalance()).isTrue();
        assertThat(journalEntryRepository.findAll()).hasSize(3);
    }
}
```

- [ ] **Step 2: Run to verify tests fail (LedgerService not yet wired into PaymentService)**

Run: `./mvnw -pl payment-api -am test -Dtest=LedgerIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: FAIL — `walletCapturePostsBalancedJournalEntry` fails with 0 journal entries found.

### 5b — Update `PaymentServicePspTest` (add ledger mock)

- [ ] **Step 3: Promote `refundRepo` to field and add `ledgerService` mock in `PaymentServicePspTest`**

Open `payment-core/src/test/java/com/kimpay/payment/core/service/PaymentServicePspTest.java`.

In the field declarations section, **add** (alongside the existing `psp`, `txnRepo`, etc.):
```java
    private RefundRepository refundRepo;
    private com.kimpay.payment.core.ledger.LedgerService ledgerService;
```

In `setUp()`, replace the existing local `var refundRepo = mock(RefundRepository.class);` with the field assignment:
```java
        refundRepo = mock(RefundRepository.class);
        ledgerService = mock(com.kimpay.payment.core.ledger.LedgerService.class);
```

Update the `new PaymentService(...)` call to pass `ledgerService` as the **last** argument:
```java
        service = new PaymentService(txnRepo, walletRepo, walletTxnRepo, methodRepo, refundRepo,
                logRepo, userRepo, merchantRepo, publisher, encryption, qr, redis, redisson, psp, ledgerService);
```

In `refundingCardTransactionCallsPspRefund`, replace `refundRepoForCard()` with `refundRepo`:
```java
        when(refundRepo.findAllByTransactionId(51L)).thenReturn(java.util.List.of());
```

- [ ] **Step 4: Run the PSP test suite to confirm it still compiles + passes**

Run: `./mvnw -pl payment-core -am test -Dtest=PaymentServicePspTest -q`
Expected: PASS (still 4 tests; won't pass yet if PaymentService constructor doesn't have `ledgerService` param — that's added in Step 5 below)

### 5c — Implement the wiring

- [ ] **Step 5: Add `ledgerService` field to `PaymentService`**

In `PaymentService.java`, add as the **last** `final` field (after `pspConnector`):
```java
    private final com.kimpay.payment.core.ledger.LedgerService ledgerService;
```

Add the import at the top:
```java
import com.kimpay.payment.core.ledger.LedgerPostingRequest;
import com.kimpay.payment.core.ledger.LedgerLineRequest;
import com.kimpay.payment.core.ledger.LedgerService;
import com.kimpay.payment.core.repository.TransactionFeeRepository;
import com.kimpay.payment.domain.entity.EntryDirection;
import com.kimpay.payment.domain.entity.EntryEventType;
```

Also add `TransactionFeeRepository` as a field (before `ledgerService`):
```java
    private final TransactionFeeRepository transactionFeeRepository;
```

- [ ] **Step 6: Add private helpers to `PaymentService` for building posting requests**

Add these two private methods at the bottom of `PaymentService` (before the closing `}`):

```java
    private LedgerPostingRequest buildCapturePosting(Transaction transaction) {
        String payerCode = transaction.getWalletId() != null
                ? "WALLET:" + transaction.getWalletId()
                : "SYS:PSP_CLEARING";
        String merchantCode = "MERCHANT:" + transaction.getMerchantId();
        String currency = transaction.getCurrency();
        BigDecimal amount = transaction.getAmount();

        BigDecimal fee = transactionFeeRepository.findAllByTransactionId(transaction.getId())
                .stream().map(com.kimpay.payment.domain.entity.TransactionFee::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<LedgerLineRequest> lines = new ArrayList<>();
        lines.add(new LedgerLineRequest(payerCode, currency, EntryDirection.DEBIT, amount));
        if (fee.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new LedgerLineRequest(merchantCode, currency, EntryDirection.CREDIT, amount.subtract(fee)));
            lines.add(new LedgerLineRequest("SYS:FEE_REVENUE", currency, EntryDirection.CREDIT, fee));
        } else {
            lines.add(new LedgerLineRequest(merchantCode, currency, EntryDirection.CREDIT, amount));
        }
        return new LedgerPostingRequest(transaction.getId(), EntryEventType.CAPTURE,
                "Capture txn " + transaction.getId(), lines);
    }

    private LedgerPostingRequest buildRefundPosting(Transaction transaction, BigDecimal refundAmount) {
        String payerCode = transaction.getWalletId() != null
                ? "WALLET:" + transaction.getWalletId()
                : "SYS:PSP_CLEARING";
        String merchantCode = "MERCHANT:" + transaction.getMerchantId();
        String currency = transaction.getCurrency();
        return new LedgerPostingRequest(transaction.getId(), EntryEventType.REFUND,
                "Refund txn " + transaction.getId(),
                List.of(
                        new LedgerLineRequest(merchantCode, currency, EntryDirection.DEBIT, refundAmount),
                        new LedgerLineRequest(payerCode, currency, EntryDirection.CREDIT, refundAmount)
                ));
    }
```

You will also need to add `import java.util.ArrayList;` at the top of `PaymentService.java` if it is not already present.

- [ ] **Step 7: Call `ledgerService.post(...)` in `completeCapture`**

In `completeCapture`, after `transaction = transactionRepository.save(transaction);`, add:
```java
        ledgerService.post(buildCapturePosting(transaction));
```

The block should look like:
```java
        transaction.capture();
        transaction = transactionRepository.save(transaction);
        ledgerService.post(buildCapturePosting(transaction));
        logEvent(transaction.getId(), "CAPTURED", "Transaction captured");
        publishEvent("CAPTURED", transaction, "Transaction captured");
        return PaymentResponse.from(transaction);
```

- [ ] **Step 8: Call `ledgerService.post(...)` in `capturePayment`**

In `capturePayment`, after `transaction = transactionRepository.save(transaction);`, add:
```java
        ledgerService.post(buildCapturePosting(transaction));
```

The block should look like:
```java
        transaction.capture();
        transaction = transactionRepository.save(transaction);
        ledgerService.post(buildCapturePosting(transaction));
        logEvent(transactionId, "CAPTURED", "Capture successful");
        publishEvent("CAPTURED", transaction, "Capture successful");
        return PaymentResponse.from(transaction);
```

- [ ] **Step 9: Call `ledgerService.post(...)` in `refundPayment`**

In `refundPayment`, after the wallet-credit `ifPresent` block (after the PSP refund call) and before the status update block, add:
```java
        ledgerService.post(buildRefundPosting(transaction, request.amount()));
```

The surrounding context should look like:
```java
        // ... (wallet credit ifPresent block ends here) ...

        ledgerService.post(buildRefundPosting(transaction, request.amount()));

        // Update transaction status based on whether it's fully or partially refunded
        if (request.amount().compareTo(remainingAmount) == 0) {
```

- [ ] **Step 10: Run the PSP unit tests to confirm they still pass**

Run: `./mvnw -pl payment-core -am test -Dtest=PaymentServicePspTest -q`
Expected: PASS (4 tests — ledgerService is mocked, so no real posting happens)

- [ ] **Step 11: Run the ledger integration tests**

Run: `./mvnw -pl payment-api -am test -Dtest=LedgerIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS (3 tests)

> **If `trialBalance` fails with H2:** `COALESCE` in JPQL is not available in all H2 versions. If the query fails, change `JournalLineRepository.sumByDirection` to:
> ```java
> @Query("SELECT SUM(l.amount) FROM JournalLine l WHERE l.direction = :direction")
> BigDecimal sumByDirection(@Param("direction") String direction);
> ```
> and in `LedgerService.trialBalance()`, the existing null-checks on the result will handle the `null` from an empty table.

- [ ] **Step 12: Commit**

```bash
git add payment-core/src/main/java/com/kimpay/payment/core/service/PaymentService.java payment-core/src/test/java/com/kimpay/payment/core/service/PaymentServicePspTest.java payment-api/src/test/java/com/kimpay/payment/LedgerIntegrationTest.java
git commit -m "feat(ledger): wire LedgerService into PaymentService capture and refund paths"
```

---

## Task 6: Full suite verification + decision log

**Files:**
- Modify: `.claude/docs/decision-log.md`

- [ ] **Step 1: Run the full test suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS, 0 failures, 0 errors across all modules. Requires local Redis; Kafka absent is fine (warnings only).

> If `DeferredCaptureIntegrationTest` fails, the most likely cause is `PaymentService`'s constructor arg count changed — verify that `PaymentServicePspTest`'s `new PaymentService(...)` call matches the real field order exactly.

- [ ] **Step 2: Confirm test counts increased**

The suite should include at least the 7 new `LedgerServiceTest` cases and 3 new `LedgerIntegrationTest` cases on top of the existing 51. Any fewer means a test wasn't discovered — check the class name and package mirror.

- [ ] **Step 3: Add decision log entry**

Append to `.claude/docs/decision-log.md`:
```markdown
## 2026-05-26 — Phase 2b: double-entry ledger

**Context:** Existing money-movement records (`WalletTransaction`, `TransactionFee`, `Refund`) are single-entry rows that don't enforce global debit/credit balance. The roadmap requires a ledger that makes it possible to detect drift between gateway state and PSP/wallet state.

**Decision:** Add a parallel double-entry ledger (`LedgerAccount`, `JournalEntry`, `JournalLine`) in `payment-domain`/`payment-core`. Posting via `LedgerService.post(LedgerPostingRequest)` is called inside the existing `@Transactional` capture and refund methods so the ledger write is atomic with the money movement. Accounts are per-owner (WALLET:{id}, MERCHANT:{id}) or system singletons (SYS:PSP_CLEARING, SYS:FEE_REVENUE, SYS:GATEWAY_CASH), seeded by Flyway V5. Balances are materialized and locked in id-sorted order (deadlock-safe pessimistic writes). Existing `WalletTransaction` rows are unchanged. AUTHORIZE and VOID produce no ledger entries (no money moves). Fee revenue is not reversed on refund (gateway keeps fee — explicit policy). `trialBalance()` returns true iff global Σdebits == Σcredits.

**Consequences:** Any future reconciliation job or settlement report reads `journal_lines` as the source of truth. Cross-currency, settlement/payout records, and PSP reconciliation-against-live-data are deferred to later Phase 2b slices.
```

- [ ] **Step 4: Commit**

```bash
git add .claude/docs/decision-log.md
git commit -m "docs(ledger): record Phase 2b double-entry ledger decision"
```

---

## Self-review notes (for the executor)

- **Field order trap:** `LedgerService` is added as the LAST field in `PaymentService` (after `pspConnector`); `TransactionFeeRepository` goes one before it. Keep `PaymentServicePspTest`'s constructor call in sync with this order or it won't compile.
- **Wallet balance semantics:** The ledger `WALLET:{id}` account records `30.00` after a $30 capture even though it's a LIABILITY. This reflects how much of the user's original wallet money has moved out (analogous to the balance being debited away), NOT the remaining wallet balance. The actual wallet balance is still authoritative in `wallets.balance`. Don't confuse the two.
- **Trial balance in H2:** `COALESCE` in JPQL may need to be replaced with a null-check in Java as described in Task 5, Step 11.
- **No PAN/secret logging:** `LedgerService` never logs account codes that could contain embedded user IDs in a way that leaks PII beyond what's already in the DB.
- **Deferred:** cross-currency entries, settlement/payout accounts, PSP reconciliation job, card-capture ledger entry (card path calls `completeCapture` which already has the post call — it will post to `SYS:PSP_CLEARING`). Verify by manually checking the card path through `completeCapture` in `createPayment`.
