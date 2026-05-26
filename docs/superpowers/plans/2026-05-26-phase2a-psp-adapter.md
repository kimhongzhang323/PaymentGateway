# Phase 2a — PSP Adapter Seam & Lifecycle Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a pluggable PSP (Payment Service Provider) seam so card payments flow through an external acquirer interface, ship a deterministic `MockAcquirerConnector` for offline/CI, and persist enough context on `Transaction` to make deferred (authorize-then-capture) flows actually move money.

**Architecture:** A `PspConnector` interface lives in `payment-core` (transport-agnostic, like `PaymentEventPublisher`). The default implementation is a deterministic `MockAcquirerConnector`, registered as a `@Bean` with `@ConditionalOnMissingBean` in a `@Configuration` class (per `architecture-principles.md`). `PaymentService` routes **card** transactions (no `walletId`) through the connector for authorize/capture/void/refund, while **wallet** transactions keep the existing internal ledger path. `Transaction` gains nullable `wallet_id` and `psp_reference` columns (Flyway `V4`) so a separated capture can find the wallet to debit or the PSP reference to capture. The concrete `StripeConnector` (test mode) is **deferred to Phase 2b** — this slice delivers the seam, the mock, and the lifecycle fix, and stays fully green in CI with no external SDK.

**Tech Stack:** Java 17, Spring Boot 3.5.x, Maven multi-module, PostgreSQL + Flyway, JPA/Hibernate, JUnit 5 + AssertJ + Mockito, H2 (PostgreSQL mode) for tests.

---

## Module placement & file structure

- **payment-core** — `PspConnector` interface, `PspAuthorizeRequest`/`PspResult`/`PspStatus` value types, `MockAcquirerConnector`, `PspConnectorConfig` (fallback bean), and the `PaymentService` wiring. (Business logic + infra interface — same module as `PaymentEventPublisher`.)
- **payment-domain** — new nullable fields on `Transaction`.
- **payment-api** — Flyway migration `V4`. (Migrations live in `payment-api/src/main/resources/db/migration/` per `database.md`.)

> **Convention reminders:** `BigDecimal` for money; records for DTOs/value objects; constructor injection via Lombok `@RequiredArgsConstructor`; never log secrets/PANs; new migration file, never edit an applied one; tests use H2 with `ddl-auto: create-drop` (new columns auto-create in tests, but the prod migration is still mandatory).

---

## Task 1: `PspConnector` interface and value types

**Files:**
- Create: `payment-core/src/main/java/com/kimpay/payment/core/psp/PspStatus.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/psp/PspAuthorizeRequest.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/psp/PspResult.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/psp/PspConnector.java`

- [ ] **Step 1: Create the status enum**

`PspStatus.java`:
```java
package com.kimpay.payment.core.psp;

/** Normalized PSP outcome, independent of any specific provider's vocabulary. */
public enum PspStatus {
    AUTHORIZED,
    CAPTURED,
    VOIDED,
    REFUNDED,
    DECLINED,
    ERROR
}
```

- [ ] **Step 2: Create the authorize request value object**

`PspAuthorizeRequest.java`:
```java
package com.kimpay.payment.core.psp;

import java.math.BigDecimal;

/**
 * Request to authorize a card payment with the PSP.
 *
 * @param transactionId   our internal transaction id (for correlation / idempotency)
 * @param paymentMethodId the stored payment-method id being charged
 * @param amount          amount to authorize (minor-unit conversion is the connector's concern)
 * @param currency        3-letter ISO currency, uppercase
 * @param capture         true to authorize-and-capture in one call; false to authorize only
 */
public record PspAuthorizeRequest(
        Long transactionId,
        Long paymentMethodId,
        BigDecimal amount,
        String currency,
        boolean capture
) {}
```

- [ ] **Step 3: Create the result value object**

`PspResult.java`:
```java
package com.kimpay.payment.core.psp;

/**
 * Outcome of a PSP operation.
 *
 * @param status        normalized status
 * @param pspReference  the PSP's reference id for this charge (null on hard error)
 * @param declineReason human-safe reason when DECLINED/ERROR; null on success. Never contains PAN/secrets.
 */
public record PspResult(PspStatus status, String pspReference, String declineReason) {

    public boolean isSuccess() {
        return status == PspStatus.AUTHORIZED
                || status == PspStatus.CAPTURED
                || status == PspStatus.VOIDED
                || status == PspStatus.REFUNDED;
    }

    public static PspResult ok(PspStatus status, String pspReference) {
        return new PspResult(status, pspReference, null);
    }

    public static PspResult declined(String pspReference, String reason) {
        return new PspResult(PspStatus.DECLINED, pspReference, reason);
    }
}
```

- [ ] **Step 4: Create the connector interface**

`PspConnector.java`:
```java
package com.kimpay.payment.core.psp;

import java.math.BigDecimal;

/**
 * Transport-agnostic seam to an external acquirer / PSP. Implementations live closer to
 * transport (e.g. a Stripe adapter in payment-api); the default offline fallback is
 * {@code MockAcquirerConnector}. Implementations MUST NOT log PANs, CVV, or secrets.
 */
public interface PspConnector {

    /** Authorize (and optionally capture) a card payment. */
    PspResult authorize(PspAuthorizeRequest request);

    /** Capture a previously-authorized payment by its PSP reference. */
    PspResult capture(String pspReference, BigDecimal amount);

    /** Void (cancel) a previously-authorized, not-yet-captured payment. */
    PspResult voidAuthorization(String pspReference);

    /** Refund a captured payment, in full or part. */
    PspResult refund(String pspReference, BigDecimal amount);
}
```

- [ ] **Step 5: Verify it compiles**

Run: `./mvnw -pl payment-core -am compile -q`
Expected: BUILD SUCCESS (no test yet — these are declarations).

- [ ] **Step 6: Commit**

```bash
git add payment-core/src/main/java/com/kimpay/payment/core/psp/
git commit -m "feat(psp): add PspConnector seam interface and value types"
```

---

## Task 2: `MockAcquirerConnector` (deterministic default) + fallback config

**Files:**
- Create: `payment-core/src/main/java/com/kimpay/payment/core/psp/MockAcquirerConnector.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/psp/PspConnectorConfig.java`
- Test: `payment-core/src/test/java/com/kimpay/payment/core/psp/MockAcquirerConnectorTest.java`

- [ ] **Step 1: Write the failing test**

`MockAcquirerConnectorTest.java`:
```java
package com.kimpay.payment.core.psp;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MockAcquirerConnectorTest {

    private final MockAcquirerConnector connector = new MockAcquirerConnector();

    @Test
    void authorizeApprovesAndReturnsReference() {
        PspResult result = connector.authorize(new PspAuthorizeRequest(1L, 9L, new BigDecimal("10.00"), "USD", false));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.status()).isEqualTo(PspStatus.AUTHORIZED);
        assertThat(result.pspReference()).startsWith("mock_");
    }

    @Test
    void authorizeWithCaptureReturnsCaptured() {
        PspResult result = connector.authorize(new PspAuthorizeRequest(1L, 9L, new BigDecimal("10.00"), "USD", true));
        assertThat(result.status()).isEqualTo(PspStatus.CAPTURED);
    }

    @Test
    void magicDeclineAmountIsDeclined() {
        // Convention: any amount whose minor units end in .01 is declined, for testing decline paths.
        PspResult result = connector.authorize(new PspAuthorizeRequest(1L, 9L, new BigDecimal("10.01"), "USD", false));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.status()).isEqualTo(PspStatus.DECLINED);
        assertThat(result.declineReason()).isNotBlank();
    }

    @Test
    void captureVoidRefundEchoReferenceAndSucceed() {
        assertThat(connector.capture("mock_abc", new BigDecimal("10.00")).status()).isEqualTo(PspStatus.CAPTURED);
        assertThat(connector.voidAuthorization("mock_abc").status()).isEqualTo(PspStatus.VOIDED);
        assertThat(connector.refund("mock_abc", new BigDecimal("5.00")).status()).isEqualTo(PspStatus.REFUNDED);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -pl payment-core -am test -Dtest=MockAcquirerConnectorTest -q`
Expected: COMPILE FAILURE (`MockAcquirerConnector` does not exist).

- [ ] **Step 3: Implement the mock connector**

`MockAcquirerConnector.java`:
```java
package com.kimpay.payment.core.psp;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Deterministic, offline PSP used for local development and CI. Approves by default; declines
 * any amount whose fractional minor units equal .01 so decline paths are testable without a
 * real acquirer. Never contacts the network. Logs no PAN/secret material.
 */
@Slf4j
public class MockAcquirerConnector implements PspConnector {

    private static final BigDecimal DECLINE_TRIGGER = new BigDecimal("0.01");

    @Override
    public PspResult authorize(PspAuthorizeRequest request) {
        if (isDeclineAmount(request.amount())) {
            log.info("[mock-psp] declining txn {} (magic decline amount)", request.transactionId());
            return PspResult.declined(newReference(), "Card declined (mock)");
        }
        PspStatus status = request.capture() ? PspStatus.CAPTURED : PspStatus.AUTHORIZED;
        return PspResult.ok(status, newReference());
    }

    @Override
    public PspResult capture(String pspReference, BigDecimal amount) {
        return PspResult.ok(PspStatus.CAPTURED, pspReference);
    }

    @Override
    public PspResult voidAuthorization(String pspReference) {
        return PspResult.ok(PspStatus.VOIDED, pspReference);
    }

    @Override
    public PspResult refund(String pspReference, BigDecimal amount) {
        return PspResult.ok(PspStatus.REFUNDED, pspReference);
    }

    private boolean isDeclineAmount(BigDecimal amount) {
        if (amount == null) {
            return false;
        }
        BigDecimal fractional = amount.remainder(BigDecimal.ONE).abs().setScale(2, java.math.RoundingMode.HALF_UP);
        return fractional.compareTo(DECLINE_TRIGGER) == 0;
    }

    private String newReference() {
        return "mock_" + UUID.randomUUID().toString().replace("-", "");
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -pl payment-core -am test -Dtest=MockAcquirerConnectorTest -q`
Expected: PASS (4 tests).

- [ ] **Step 5: Add the fallback config bean**

`PspConnectorConfig.java`:
```java
package com.kimpay.payment.core.psp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the default {@link PspConnector} when no other implementation (e.g. a Stripe adapter
 * in payment-api) is present. Declared as a {@code @Bean} with {@code @ConditionalOnMissingBean}
 * per architecture-principles.md — never a scanned conditional {@code @Component}.
 */
@Configuration
public class PspConnectorConfig {

    @Bean
    @ConditionalOnMissingBean(PspConnector.class)
    public PspConnector mockAcquirerConnector() {
        return new MockAcquirerConnector();
    }
}
```

> **Dependency note:** `payment-core` already uses `@ConditionalOnMissingBean` in `PaymentEventPublisherConfig`, so `spring-boot-autoconfigure` is already on its classpath. If `compile` reports the annotation missing, STOP and report — do not add a new dependency without confirming.

- [ ] **Step 6: Verify compile + commit**

Run: `./mvnw -pl payment-core -am test -Dtest=MockAcquirerConnectorTest -q`
Expected: PASS.

```bash
git add payment-core/src/main/java/com/kimpay/payment/core/psp/ payment-core/src/test/java/com/kimpay/payment/core/psp/
git commit -m "feat(psp): deterministic MockAcquirerConnector with conditional fallback bean"
```

---

## Task 3: Persist `walletId` and `pspReference` on `Transaction` (+ migration V4)

**Files:**
- Modify: `payment-domain/src/main/java/com/kimpay/payment/domain/entity/Transaction.java` (add two fields after `paymentMethodId`)
- Create: `payment-api/src/main/resources/db/migration/V4__add_transaction_wallet_and_psp_ref.sql`
- Test: `payment-api/src/test/java/com/kimpay/payment/TransactionContextPersistenceTest.java`

- [ ] **Step 1: Write the failing test**

`TransactionContextPersistenceTest.java`:
```java
package com.kimpay.payment;

import com.kimpay.payment.core.repository.TransactionRepository;
import com.kimpay.payment.domain.entity.Transaction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "payment.encryption.key-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="
})
class TransactionContextPersistenceTest {

    @Autowired
    private TransactionRepository transactionRepository;

    @Test
    void persistsWalletIdAndPspReference() {
        Transaction t = new Transaction();
        t.setUserId(1L);
        t.setMerchantId(2L);
        t.setAmount(new BigDecimal("10.00"));
        t.setCurrency("USD");
        t.setWalletId(7L);
        t.setPspReference("mock_xyz");
        t.authorize();

        Transaction saved = transactionRepository.save(t);
        Transaction reloaded = transactionRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getWalletId()).isEqualTo(7L);
        assertThat(reloaded.getPspReference()).isEqualTo("mock_xyz");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -pl payment-api -am test -Dtest=TransactionContextPersistenceTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: COMPILE FAILURE (`setWalletId`/`setPspReference` undefined).

- [ ] **Step 3: Add the fields to `Transaction`**

In `Transaction.java`, immediately after the existing `paymentMethodId` field (around line 54), add:
```java
    @Column(name = "wallet_id")
    private Long walletId;

    @Column(name = "psp_reference", length = 100)
    private String pspReference;
```
> The class uses Lombok accessors (it already exposes `getStatus()`, `setMerchantId()`, etc.). Do not hand-write getters/setters unless the class lacks `@Getter/@Setter` — match the existing style in the file.

- [ ] **Step 4: Write the prod migration**

`V4__add_transaction_wallet_and_psp_ref.sql`:
```sql
-- Persist enough context on a transaction to support deferred capture and PSP reconciliation.
ALTER TABLE transactions
    ADD COLUMN wallet_id     BIGINT,
    ADD COLUMN psp_reference VARCHAR(100);

-- Look up a transaction by its PSP reference when reconciling inbound PSP webhooks (Phase 2b).
CREATE INDEX IF NOT EXISTS idx_transactions_psp_reference ON transactions (psp_reference);
```
> Confirm the table name matches the existing entity mapping. If the table is not `transactions`, use the actual `@Table` name — STOP and check rather than guessing.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -pl payment-api -am test -Dtest=TransactionContextPersistenceTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS (H2 auto-creates the columns from the entity; Flyway is disabled in tests).

- [ ] **Step 6: Commit**

```bash
git add payment-domain/src/main/java/com/kimpay/payment/domain/entity/Transaction.java payment-api/src/main/resources/db/migration/V4__add_transaction_wallet_and_psp_ref.sql payment-api/src/test/java/com/kimpay/payment/TransactionContextPersistenceTest.java
git commit -m "feat(psp): persist walletId and pspReference on Transaction (migration V4)"
```

---

## Task 4: Route card-path `createPayment` through the connector; persist context

**Files:**
- Modify: `payment-core/src/main/java/com/kimpay/payment/core/service/PaymentService.java`
- Test: `payment-core/src/test/java/com/kimpay/payment/core/service/PaymentServicePspTest.java`

**Context:** Today `processCreatePayment` sets fields and calls `completeCapture`, which for a card (no `walletId`) only calls `processPaymentMethod` (a status check — no real charge). We will (a) persist `walletId` on the transaction, and (b) for the card path, call `pspConnector.authorize(...)`, store the returned `pspReference`, and fail the transaction on decline.

- [ ] **Step 1: Inject the connector**

In `PaymentService`, add to the final fields (constructor injection via `@RequiredArgsConstructor`):
```java
    private final com.kimpay.payment.core.psp.PspConnector pspConnector;
```
> Add the import at the top with the others: `import com.kimpay.payment.core.psp.*;`

- [ ] **Step 2: Persist `walletId` when building the transaction**

In `processCreatePayment`, where the `Transaction` is populated (after `transaction.setPaymentMethodId(request.paymentMethodId());`), add:
```java
        transaction.setWalletId(request.walletId());
```

- [ ] **Step 3: Write the failing test**

`PaymentServicePspTest.java` (unit test with mocked collaborators — mirror the existing `ApiKeyServiceTest` Mockito style; mock every repository + `PspConnector` + `RedissonClient` + `StringRedisTemplate`):
```java
package com.kimpay.payment.core.service;

import com.kimpay.payment.core.dto.CreatePaymentRequest;
import com.kimpay.payment.core.dto.PaymentResponse;
import com.kimpay.payment.core.psp.*;
import com.kimpay.payment.core.repository.*;
import com.kimpay.payment.domain.entity.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentServicePspTest {

    private PspConnector psp;
    private TransactionRepository txnRepo;
    private UserRepository userRepo;
    private MerchantRepository merchantRepo;
    private PaymentMethodRepository methodRepo;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        psp = mock(PspConnector.class);
        txnRepo = mock(TransactionRepository.class);
        userRepo = mock(UserRepository.class);
        merchantRepo = mock(MerchantRepository.class);
        methodRepo = mock(PaymentMethodRepository.class);
        var walletRepo = mock(WalletRepository.class);
        var walletTxnRepo = mock(WalletTransactionRepository.class);
        var refundRepo = mock(RefundRepository.class);
        var logRepo = mock(TransactionLogRepository.class);
        var publisher = mock(com.kimpay.payment.core.event.PaymentEventPublisher.class);
        var encryption = mock(com.kimpay.payment.security.EncryptionService.class);
        var qr = mock(QRService.class);
        var redis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        var redisson = mock(org.redisson.api.RedissonClient.class);

        service = new PaymentService(txnRepo, walletRepo, walletTxnRepo, methodRepo, refundRepo,
                logRepo, userRepo, merchantRepo, publisher, encryption, qr, redis, redisson, psp);

        when(userRepo.existsById(anyLong())).thenReturn(true);
        when(merchantRepo.existsById(anyLong())).thenReturn(true);
        when(redis.hasKey(anyString())).thenReturn(false);
        when(txnRepo.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            if (t.getId() == null) t.setId(100L);
            return t;
        });
        var method = new com.kimpay.payment.domain.entity.PaymentMethod();
        method.setStatus("active");
        when(methodRepo.findByIdAndUserId(anyLong(), anyLong())).thenReturn(java.util.Optional.of(method));
    }

    @Test
    void cardPaymentAuthorizesThroughPspAndStoresReference() {
        when(psp.authorize(any())).thenReturn(PspResult.ok(PspStatus.CAPTURED, "mock_ref_1"));
        // card path: walletId == null, paymentMethodId set
        CreatePaymentRequest req = new CreatePaymentRequest(1L, 2L, 9L, null,
                new BigDecimal("10.00"), "USD", true, null);

        PaymentResponse resp = service.createPayment(req);

        ArgumentCaptor<PspAuthorizeRequest> cap = ArgumentCaptor.forClass(PspAuthorizeRequest.class);
        verify(psp).authorize(cap.capture());
        assertThat(cap.getValue().amount()).isEqualByComparingTo("10.00");
        assertThat(cap.getValue().capture()).isTrue();
        assertThat(resp.status()).isEqualTo("CAPTURED");

        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(txnRepo, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).anyMatch(t -> "mock_ref_1".equals(t.getPspReference()));
    }

    @Test
    void declinedCardPaymentMarksTransactionFailed() {
        when(psp.authorize(any())).thenReturn(PspResult.declined("mock_ref_2", "Card declined (mock)"));
        CreatePaymentRequest req = new CreatePaymentRequest(1L, 2L, 9L, null,
                new BigDecimal("10.00"), "USD", true, null);

        assertThatThrownBy(() -> service.createPayment(req))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(txnRepo, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).anyMatch(t -> "FAILED".equals(t.getStatus()));
    }
}
```
> **Before writing:** open `PaymentService`'s constructor and confirm the parameter order matches the `new PaymentService(...)` call above; Lombok generates the constructor in field-declaration order, so `pspConnector` must be the LAST field (Step 1 added it last). Adjust the test's argument order to the real field order if needed.

- [ ] **Step 2b: Run it to verify it fails**

Run: `./mvnw -pl payment-core -am test -Dtest=PaymentServicePspTest -q`
Expected: FAIL — card path does not call `psp.authorize` yet (verify fails), and decline does not throw.

- [ ] **Step 3: Implement the card path in `completeCapture`**

Replace the `else` branch (the `processPaymentMethod` call) in `completeCapture` so the card path goes through the PSP. The method currently reads:
```java
        if (walletId != null) {
            processWalletDebit(walletId, transaction.getUserId(), transaction.getAmount(), transaction.getCurrency(), transaction.getId());
        } else {
            Long methodId = paymentMethodId != null ? paymentMethodId : transaction.getPaymentMethodId();
            if (methodId == null) {
                 throw new IllegalArgumentException("Payment method information is missing");
            }
            processPaymentMethod(methodId, transaction.getUserId());
        }

        transaction.capture();
```
Change the `else` branch to:
```java
        } else {
            Long methodId = paymentMethodId != null ? paymentMethodId : transaction.getPaymentMethodId();
            if (methodId == null) {
                 throw new IllegalArgumentException("Payment method information is missing");
            }
            processPaymentMethod(methodId, transaction.getUserId()); // validates method is active
            PspResult auth = pspConnector.authorize(new PspAuthorizeRequest(
                    transaction.getId(), methodId, transaction.getAmount(), transaction.getCurrency(), true));
            transaction.setPspReference(auth.pspReference());
            if (!auth.isSuccess()) {
                throw new IllegalStateException("Payment authorization declined");
            }
        }

        transaction.capture();
```
> Note: in the auto-capture flow we pass `capture=true`. The decline throws `IllegalStateException`; the existing `processCreatePayment` `catch (RuntimeException ex)` block already marks the transaction `FAILED`, saves it, and re-throws — so the decline test's "FAILED" assertion is satisfied by existing code. Confirm that catch block still wraps `completeCapture`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -pl payment-core -am test -Dtest=PaymentServicePspTest -q`
Expected: PASS (2 tests).

- [ ] **Step 5: Run the broader core suite to catch regressions**

Run: `./mvnw -pl payment-core -am test -q`
Expected: PASS (existing wallet-path tests unaffected — they pass `walletId`, so they never hit the PSP branch).

- [ ] **Step 6: Commit**

```bash
git add payment-core/src/main/java/com/kimpay/payment/core/service/PaymentService.java payment-core/src/test/java/com/kimpay/payment/core/service/PaymentServicePspTest.java
git commit -m "feat(psp): route card-path createPayment through PspConnector, persist reference and walletId"
```

---

## Task 5: Fix deferred `capturePayment` (wallet debit + PSP capture)

**Files:**
- Modify: `payment-core/src/main/java/com/kimpay/payment/core/service/PaymentService.java` (the `capturePayment` method)
- Test: `payment-api/src/test/java/com/kimpay/payment/DeferredCaptureIntegrationTest.java`

**Context:** `capturePayment` currently just flips status to CAPTURED without moving money — the long-standing TODO. Now that `walletId` and `pspReference` are persisted, a separated capture can do the real work: debit the stored wallet, or capture the stored PSP reference.

- [ ] **Step 1: Write the failing integration test**

`DeferredCaptureIntegrationTest.java` (real Spring context, H2; mirror `PaymentControllerIntegrationTest` setup for User/Merchant/Wallet creation and FK-safe `@BeforeEach` cleanup; call `PaymentService` directly):
```java
package com.kimpay.payment;

import com.kimpay.payment.core.dto.CreatePaymentRequest;
import com.kimpay.payment.core.dto.PaymentResponse;
import com.kimpay.payment.core.repository.*;
import com.kimpay.payment.core.service.PaymentService;
import com.kimpay.payment.domain.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "payment.encryption.key-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="
})
class DeferredCaptureIntegrationTest {

    @Autowired PaymentService paymentService;
    @Autowired UserRepository userRepository;
    @Autowired MerchantRepository merchantRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired WalletTransactionRepository walletTransactionRepository;
    @Autowired RefundRepository refundRepository;

    Long userId, merchantId, walletId;

    @BeforeEach
    void setUp() {
        refundRepository.deleteAll();
        walletTransactionRepository.deleteAll();
        transactionRepository.deleteAll();
        walletRepository.deleteAll();
        merchantRepository.deleteAll();
        userRepository.deleteAll();

        User u = new User(); u.setName("U"); u.setEmail("u@test.com"); u.setPasswordHash("h"); u.setRoleId(1L);
        userId = userRepository.save(u).getId();
        Merchant m = new Merchant(); m.setUserId(userId); m.setBusinessName("M"); m.setStatus("active");
        merchantId = merchantRepository.save(m).getId();
        Wallet w = new Wallet(); w.setUserId(userId); w.setCurrency("USD"); w.setBalance(new BigDecimal("100.00"));
        walletId = walletRepository.save(w).getId();
    }

    @Test
    void deferredWalletCaptureDebitsTheStoredWallet() {
        // authorize only (capture=false) against the wallet
        CreatePaymentRequest authReq = new CreatePaymentRequest(
                userId, merchantId, null, walletId, new BigDecimal("25.00"), "USD", false, null);
        PaymentResponse authorized = paymentService.createPayment(authReq);
        assertThat(authorized.status()).isEqualTo("AUTHORIZED");
        // balance untouched at authorize time
        assertThat(walletRepository.findById(walletId).orElseThrow().getBalance()).isEqualByComparingTo("100.00");

        // separated capture must now actually debit the wallet
        PaymentResponse captured = paymentService.capturePayment(authorized.id());
        assertThat(captured.status()).isEqualTo("CAPTURED");
        assertThat(walletRepository.findById(walletId).orElseThrow().getBalance()).isEqualByComparingTo("75.00");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -pl payment-api -am test -Dtest=DeferredCaptureIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: FAIL — balance stays 100.00 after capture (current `capturePayment` flips status only). Requires local Redis running.

- [ ] **Step 3: Implement real deferred capture**

Replace the body of `capturePayment` (the simplified status-only version) with:
```java
    @Transactional
    public PaymentResponse capturePayment(Long transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(ResourceNotFoundException::new);

        if (!PaymentStatus.AUTHORIZED.name().equals(transaction.getStatus())) {
            throw new IllegalStateException("Only AUTHORIZED transactions can be captured");
        }

        if (transaction.getWalletId() != null) {
            // Wallet-backed: perform the real debit now, using the persisted wallet.
            processWalletDebit(transaction.getWalletId(), transaction.getUserId(),
                    transaction.getAmount(), transaction.getCurrency(), transaction.getId());
        } else {
            // Card-backed: capture the previously-authorized PSP reference.
            PspResult result = pspConnector.capture(transaction.getPspReference(), transaction.getAmount());
            if (!result.isSuccess()) {
                throw new IllegalStateException("PSP capture failed");
            }
        }

        transaction.capture();
        transaction = transactionRepository.save(transaction);
        logEvent(transactionId, "CAPTURED", "Capture successful");
        publishEvent("CAPTURED", transaction, "Capture successful");
        return PaymentResponse.from(transaction);
    }
```
> Remove the old explanatory comment block about not storing the wallet — it no longer applies.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -pl payment-api -am test -Dtest=DeferredCaptureIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS — balance is 75.00 after capture.

- [ ] **Step 5: Commit**

```bash
git add payment-core/src/main/java/com/kimpay/payment/core/service/PaymentService.java payment-api/src/test/java/com/kimpay/payment/DeferredCaptureIntegrationTest.java
git commit -m "fix(psp): deferred capture debits stored wallet or captures stored PSP reference"
```

---

## Task 6: Route card-path `void` and `refund` through the connector

**Files:**
- Modify: `payment-core/src/main/java/com/kimpay/payment/core/service/PaymentService.java` (`voidPayment`, `refundPayment`)
- Test: `payment-core/src/test/java/com/kimpay/payment/core/service/PaymentServicePspTest.java` (add cases)

**Context:** For card-backed transactions (those with a `pspReference` and no `walletId`), void and refund must call the PSP. Wallet-backed transactions keep the existing internal credit logic. Wallet-backed transactions have `walletId != null`; card-backed have `walletId == null`.

- [ ] **Step 1: Add failing tests to `PaymentServicePspTest`**

```java
    @Test
    void voidingCardTransactionCallsPspVoid() {
        Transaction t = new Transaction();
        t.setId(50L); t.setUserId(1L); t.setMerchantId(2L);
        t.setAmount(new BigDecimal("10.00")); t.setCurrency("USD");
        t.setPspReference("mock_ref_v"); // card-backed: walletId stays null
        t.authorize();
        when(txnRepo.findById(50L)).thenReturn(java.util.Optional.of(t));
        when(psp.voidAuthorization("mock_ref_v")).thenReturn(PspResult.ok(PspStatus.VOIDED, "mock_ref_v"));

        service.voidPayment(50L);

        verify(psp).voidAuthorization("mock_ref_v");
    }

    @Test
    void refundingCardTransactionCallsPspRefund() {
        Transaction t = new Transaction();
        t.setId(51L); t.setUserId(1L); t.setMerchantId(2L);
        t.setAmount(new BigDecimal("10.00")); t.setCurrency("USD");
        t.setPspReference("mock_ref_r"); // card-backed
        t.authorize(); t.capture();
        when(txnRepo.findById(51L)).thenReturn(java.util.Optional.of(t));
        when(refundRepoForCard().findAllByTransactionId(51L)).thenReturn(java.util.List.of());
        when(psp.refund(eq("mock_ref_r"), any())).thenReturn(PspResult.ok(PspStatus.REFUNDED, "mock_ref_r"));

        service.refundPayment(51L, new com.kimpay.payment.core.dto.RefundPaymentRequest(new BigDecimal("10.00"), "x"));

        verify(psp).refund(eq("mock_ref_r"), any());
    }
```
> The `refundRepo` mock is created in `setUp` as a local variable; promote it to a field `private RefundRepository refundRepo;` assigned in `setUp` so these tests can stub it. Replace the placeholder `refundRepoForCard()` above with the `refundRepo` field. (Fix the field promotion when you add these tests.)

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -pl payment-core -am test -Dtest=PaymentServicePspTest -q`
Expected: FAIL — `psp.voidAuthorization` / `psp.refund` never invoked.

- [ ] **Step 3: Implement the card branches**

In `voidPayment`, before `transaction.voidTransaction();`, add:
```java
        if (transaction.getWalletId() == null && transaction.getPspReference() != null) {
            PspResult result = pspConnector.voidAuthorization(transaction.getPspReference());
            if (!result.isSuccess()) {
                throw new IllegalStateException("PSP void failed");
            }
        }
```

In `refundPayment`, after the remaining-amount validation and `refundRepository.save(refund);`, and BEFORE the existing wallet-credit block, add the card branch; keep the existing wallet-credit `ifPresent` block for wallet-backed transactions:
```java
        if (transaction.getWalletId() == null && transaction.getPspReference() != null) {
            PspResult result = pspConnector.refund(transaction.getPspReference(), request.amount());
            if (!result.isSuccess()) {
                throw new IllegalStateException("PSP refund failed");
            }
        }
```
> The existing wallet-credit block keys off `walletTransactionRepository.findFirstByTransactionIdAndType...`, which only finds rows for wallet-backed transactions — so card-backed refunds skip it naturally. Do not remove it.

- [ ] **Step 4: Run to verify pass**

Run: `./mvnw -pl payment-core -am test -Dtest=PaymentServicePspTest -q`
Expected: PASS (4 tests total in the class).

- [ ] **Step 5: Commit**

```bash
git add payment-core/src/main/java/com/kimpay/payment/core/service/PaymentService.java payment-core/src/test/java/com/kimpay/payment/core/service/PaymentServicePspTest.java
git commit -m "feat(psp): route card-path void and refund through PspConnector"
```

---

## Task 7: Full-suite verification

**Files:** none (verification only).

- [ ] **Step 1: Run the entire suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS. All Phase 1 tests plus the new PSP tests pass. Requires local Redis; Kafka may be down (warnings only).

- [ ] **Step 2: Confirm aggregate counts**

Confirm 0 failures / 0 errors across modules. If any wallet-path test regressed, the most likely cause is the `completeCapture` edit accidentally affecting the `walletId != null` branch — re-check that only the `else` branch changed.

- [ ] **Step 3: Commit (if any incidental fixes were needed)**

Only commit if Step 1 required a fix; otherwise skip.

---

## Task 8: Decision log + documentation

**Files:**
- Modify: `.claude/docs/decision-log.md`
- Modify: `ARCHITECTURE.md` (extend the security/infrastructure section with a short "PSP Adapter" note)

- [ ] **Step 1: Add a decision-log entry**

Append to `.claude/docs/decision-log.md`:
```markdown
## 2026-05-26 — Phase 2a: pluggable PSP adapter seam + lifecycle fix
**Context:** Card payments only validated the stored method (no real charge), and deferred capture could not move money because the wallet/method context was not persisted on the transaction.
**Decision:** Introduce a transport-agnostic `PspConnector` interface in payment-core (mirroring `PaymentEventPublisher`), with a deterministic `MockAcquirerConnector` as the default `@ConditionalOnMissingBean` fallback. Persist `wallet_id` and `psp_reference` on `Transaction` (Flyway V4). Route card-path authorize/capture/void/refund through the connector; wallet-path keeps the internal ledger logic. Deferred capture now debits the persisted wallet or captures the stored PSP reference. The concrete `StripeConnector` (test mode) is deferred to Phase 2b — this slice ships the seam and stays green offline/CI.
**Consequences:** Card flows are now PSP-mediated and reconcilable by `psp_reference`; the long-standing deferred-capture TODO is closed. Mock declines any amount with .01 minor units for decline-path testing. Phase 2b adds the real Stripe adapter (selected by property), inbound Stripe webhook verification, and the double-entry ledger.
```

- [ ] **Step 2: Add a short ARCHITECTURE.md note**

Under the Security & Infrastructure section, add a "PSP Adapter (Phase 2a)" subsection summarizing: the `PspConnector` seam, the mock default, property-selectable real providers (Phase 2b), and that `Transaction.psp_reference` is the reconciliation key.

- [ ] **Step 3: Commit**

```bash
git add .claude/docs/decision-log.md ARCHITECTURE.md
git commit -m "docs(psp): record Phase 2a PSP adapter decision and architecture note"
```

---

## Self-review notes (for the executor)

- **Spec coverage:** This plan covers the roadmap's "Pluggable PSP adapter" (interface + Mock) and "Lifecycle hardening" (persist context, fix deferred capture). It deliberately DEFERS to Phase 2b: the concrete `StripeConnector`, 3-D Secure, outbound merchant webhooks, inbound PSP webhooks, and the double-entry ledger. Do not implement those here.
- **Money correctness:** Wallet debits remain guarded by the existing Redisson lock + pessimistic DB lock in `processWalletDebit`. Deferred capture reuses that same path — do not bypass it.
- **No PAN/secret logging:** `MockAcquirerConnector` and any future connector must never log card data; route any risky string through `SensitiveDataMasker`.
- **Constructor order trap:** `pspConnector` is added as the LAST `@RequiredArgsConstructor` field; keep the `PaymentServicePspTest` constructor-arg order in sync with the real field order, or the unit test will not compile.
