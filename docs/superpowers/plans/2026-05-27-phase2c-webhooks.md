# Phase 2c — Inbound PSP Webhooks & Outbound Merchant Webhooks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close two async loops: (1) accept signed PSP webhook events to reconcile Transaction state, (2) deliver signed webhook notifications to merchant endpoints with exponential-backoff retry and dead-letter handling.

**Architecture:** Slice 1 adds a public `POST /api/webhooks/psp` endpoint (verified by HMAC-SHA256 using `PSP_WEBHOOK_SECRET` env var) that reconciles `Transaction` state and persists an idempotency record. Slice 2 adds `WebhookEndpoint` + `WebhookDelivery` entities, a `@Scheduled` poller that signs and dispatches HTTP POSTs to merchant URLs, and wires the existing `PaymentEventListener` to create delivery rows on each payment event.

**Tech Stack:** Java 17, Spring Boot 3.5.x, Maven multi-module, PostgreSQL + Flyway, H2 (tests), JUnit 5 + AssertJ + Mockito, WireMock (integration tests), `EncryptedStringConverter` (AES-256-GCM for webhook secrets), `RestClient` (HTTP dispatch).

---

## File Map

**Slice 1 — Inbound PSP Webhooks**
- Create: `payment-api/src/main/resources/db/migration/V6__psp_webhook_events.sql`
- Create: `payment-domain/src/main/java/com/kimpay/payment/domain/entity/PspWebhookEvent.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/repository/PspWebhookEventRepository.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/webhook/inbound/PspWebhookPayload.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/webhook/inbound/PspWebhookService.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/webhook/HmacSigningService.java`
- Create: `payment-api/src/main/java/com/kimpay/payment/controller/PspWebhookController.java`
- Modify: `payment-api/src/main/java/com/kimpay/payment/security/SecurityConfig.java`
- Create: `payment-core/src/test/java/com/kimpay/payment/core/webhook/inbound/PspWebhookServiceTest.java`
- Create: `payment-core/src/test/java/com/kimpay/payment/core/webhook/HmacSigningServiceTest.java`
- Create: `payment-api/src/test/java/com/kimpay/payment/PspWebhookIntegrationTest.java`

**Slice 2 — Outbound Merchant Webhooks**
- Create: `payment-api/src/main/resources/db/migration/V7__webhook_tables.sql`
- Create: `payment-domain/src/main/java/com/kimpay/payment/domain/entity/WebhookDeliveryStatus.java`
- Create: `payment-domain/src/main/java/com/kimpay/payment/domain/entity/WebhookEndpoint.java`
- Create: `payment-domain/src/main/java/com/kimpay/payment/domain/entity/WebhookDelivery.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/repository/WebhookEndpointRepository.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/repository/WebhookDeliveryRepository.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/webhook/outbound/WebhookDispatchService.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/webhook/outbound/WebhookRetryScheduler.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/dto/RegisterWebhookEndpointRequest.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/service/WebhookEndpointService.java`
- Modify: `payment-api/src/main/java/com/kimpay/payment/event/PaymentEventListener.java`
- Create: `payment-api/src/main/java/com/kimpay/payment/controller/WebhookEndpointController.java`
- Modify: `payment-api/pom.xml` — add WireMock test dependency
- Create: `payment-core/src/test/java/com/kimpay/payment/core/webhook/outbound/WebhookDispatchServiceTest.java`
- Create: `payment-api/src/test/java/com/kimpay/payment/WebhookDeliveryIntegrationTest.java`

---

## Task 1: WireMock test dependency

WireMock is needed in integration tests for mocking merchant HTTP endpoints. Add it before writing tests.

**Files:**
- Modify: `payment-api/pom.xml`

- [ ] **Step 1: Add WireMock dependency**

In `payment-api/pom.xml`, inside `<dependencies>`, add after the `spring-security-test` block:

```xml
<dependency>
    <groupId>org.wiremock</groupId>
    <artifactId>wiremock-standalone</artifactId>
    <version>3.5.4</version>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Verify the project still builds**

```
./mvnw clean package -DskipTests -pl payment-api -am
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```
git add payment-api/pom.xml
git commit -m "chore(deps): add WireMock test dependency for webhook integration tests"
```

---

## Task 2: `HmacSigningService` — shared HMAC-SHA256 utility

Both inbound verification and outbound signing use HMAC-SHA256 with the same header format. Extract this into one place.

**Files:**
- Create: `payment-core/src/main/java/com/kimpay/payment/core/webhook/HmacSigningService.java`
- Create: `payment-core/src/test/java/com/kimpay/payment/core/webhook/HmacSigningServiceTest.java`

- [ ] **Step 1: Write the failing unit test**

Create `payment-core/src/test/java/com/kimpay/payment/core/webhook/HmacSigningServiceTest.java`:

```java
package com.kimpay.payment.core.webhook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HmacSigningServiceTest {

    private final HmacSigningService signer = new HmacSigningService();
    private static final String SECRET = "test-secret-key";

    @Test
    void sign_producesConsistentHexSignature() {
        String sig1 = signer.sign("hello", SECRET);
        String sig2 = signer.sign("hello", SECRET);
        assertThat(sig1).isEqualTo(sig2);
        assertThat(sig1).matches("[0-9a-f]{64}");
    }

    @Test
    void verify_acceptsValidSignature() {
        String body = "payload-body";
        long ts = System.currentTimeMillis() / 1000;
        String sig = signer.sign(ts + "." + body, SECRET);
        assertThat(signer.verify(body, ts, sig, SECRET, 300)).isTrue();
    }

    @Test
    void verify_rejectsInvalidSignature() {
        long ts = System.currentTimeMillis() / 1000;
        assertThat(signer.verify("body", ts, "sha256=badhex", SECRET, 300)).isFalse();
    }

    @Test
    void verify_rejectsStaletimestamp() {
        String body = "payload";
        long staleTs = (System.currentTimeMillis() / 1000) - 400;
        String sig = signer.sign(staleTs + "." + body, SECRET);
        assertThat(signer.verify(body, staleTs, sig, SECRET, 300)).isFalse();
    }

    @Test
    void verify_rejectsFutureTimestamp() {
        String body = "payload";
        long futureTs = (System.currentTimeMillis() / 1000) + 400;
        String sig = signer.sign(futureTs + "." + body, SECRET);
        assertThat(signer.verify(body, futureTs, sig, SECRET, 300)).isFalse();
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```
./mvnw -pl payment-core -am test -Dtest=HmacSigningServiceTest
```

Expected: compile error — `HmacSigningService` does not exist.

- [ ] **Step 3: Implement `HmacSigningService`**

Create `payment-core/src/main/java/com/kimpay/payment/core/webhook/HmacSigningService.java`:

```java
package com.kimpay.payment.core.webhook;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Service
public class HmacSigningService {

    private static final String ALGORITHM = "HmacSHA256";

    /** Signs {@code message} with {@code secret} and returns a lowercase hex string (no prefix). */
    public String sign(String message, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }

    /**
     * Verifies an inbound signature.
     * Expected signature format: bare hex (no "sha256=" prefix).
     * Signed message: {@code timestamp + "." + body}.
     */
    public boolean verify(String body, long timestampSeconds, String signature, String secret, long toleranceSeconds) {
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - timestampSeconds) > toleranceSeconds) {
            return false;
        }
        String expected = sign(timestampSeconds + "." + body, secret);
        // Strip optional "sha256=" prefix for flexibility
        String candidate = signature.startsWith("sha256=") ? signature.substring(7) : signature;
        return constantTimeEquals(expected, candidate);
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```
./mvnw -pl payment-core -am test -Dtest=HmacSigningServiceTest
```

Expected: all 5 tests pass.

- [ ] **Step 5: Commit**

```
git add payment-core/src/main/java/com/kimpay/payment/core/webhook/HmacSigningService.java \
        payment-core/src/test/java/com/kimpay/payment/core/webhook/HmacSigningServiceTest.java
git commit -m "feat(webhook): add HmacSigningService for HMAC-SHA256 sign/verify"
```

---

## Task 3: Flyway V6 — `psp_webhook_events` table

**Files:**
- Create: `payment-api/src/main/resources/db/migration/V6__psp_webhook_events.sql`

- [ ] **Step 1: Create migration**

Create `payment-api/src/main/resources/db/migration/V6__psp_webhook_events.sql`:

```sql
CREATE TABLE psp_webhook_events (
    id           BIGSERIAL    PRIMARY KEY,
    event_id     VARCHAR(100) NOT NULL UNIQUE,
    event_type   VARCHAR(100) NOT NULL,
    payload      TEXT         NOT NULL,
    processed_at TIMESTAMP    NOT NULL
);
```

- [ ] **Step 2: Commit**

```
git add payment-api/src/main/resources/db/migration/V6__psp_webhook_events.sql
git commit -m "feat(webhook): Flyway V6 — psp_webhook_events table"
```

---

## Task 4: `PspWebhookEvent` entity and repository

**Files:**
- Create: `payment-domain/src/main/java/com/kimpay/payment/domain/entity/PspWebhookEvent.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/repository/PspWebhookEventRepository.java`

- [ ] **Step 1: Create the entity**

Create `payment-domain/src/main/java/com/kimpay/payment/domain/entity/PspWebhookEvent.java`:

```java
package com.kimpay.payment.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@Table(name = "psp_webhook_events")
public class PspWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 100)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;
}
```

- [ ] **Step 2: Create the repository**

Create `payment-core/src/main/java/com/kimpay/payment/core/repository/PspWebhookEventRepository.java`:

```java
package com.kimpay.payment.core.repository;

import com.kimpay.payment.domain.entity.PspWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PspWebhookEventRepository extends JpaRepository<PspWebhookEvent, Long> {
    boolean existsByEventId(String eventId);
}
```

- [ ] **Step 3: Commit**

```
git add payment-domain/src/main/java/com/kimpay/payment/domain/entity/PspWebhookEvent.java \
        payment-core/src/main/java/com/kimpay/payment/core/repository/PspWebhookEventRepository.java
git commit -m "feat(webhook): PspWebhookEvent entity and repository"
```

---

## Task 5: `PspWebhookPayload` record and `PspWebhookService`

**Files:**
- Create: `payment-core/src/main/java/com/kimpay/payment/core/webhook/inbound/PspWebhookPayload.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/webhook/inbound/PspWebhookService.java`
- Create: `payment-core/src/test/java/com/kimpay/payment/core/webhook/inbound/PspWebhookServiceTest.java`

- [ ] **Step 1: Write the failing unit test**

Create `payment-core/src/test/java/com/kimpay/payment/core/webhook/inbound/PspWebhookServiceTest.java`:

```java
package com.kimpay.payment.core.webhook.inbound;

import com.kimpay.payment.core.event.PaymentEvent;
import com.kimpay.payment.core.event.PaymentEventPublisher;
import com.kimpay.payment.core.repository.PspWebhookEventRepository;
import com.kimpay.payment.core.repository.TransactionRepository;
import com.kimpay.payment.core.webhook.HmacSigningService;
import com.kimpay.payment.domain.entity.PaymentStatus;
import com.kimpay.payment.domain.entity.PspWebhookEvent;
import com.kimpay.payment.domain.entity.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PspWebhookServiceTest {

    @Mock PspWebhookEventRepository eventRepo;
    @Mock TransactionRepository transactionRepo;
    @Mock PaymentEventPublisher publisher;

    HmacSigningService hmac = new HmacSigningService();
    PspWebhookService service;

    private static final String SECRET = "test-webhook-secret";
    private static final long TOLERANCE = 300L;

    @BeforeEach
    void setUp() {
        service = new PspWebhookService(eventRepo, transactionRepo, publisher, hmac, SECRET, TOLERANCE);
    }

    private String sign(long ts, String body) {
        return "sha256=" + hmac.sign(ts + "." + body, SECRET);
    }

    @Test
    void process_validCaptureEvent_updatesTransactionToCaptured() {
        long ts = Instant.now().getEpochSecond();
        PspWebhookPayload payload = new PspWebhookPayload("evt-1", "PAYMENT_CAPTURED", 42L, "CAPTURED", BigDecimal.TEN, "USD");
        String body = """
                {"event_id":"evt-1","event_type":"PAYMENT_CAPTURED","transaction_id":42,"status":"CAPTURED","amount":10,"currency":"USD"}
                """.strip();
        String sig = sign(ts, body);

        Transaction tx = new Transaction();
        tx.setId(42L);
        tx.setMerchantId(1L);
        tx.setUserId(1L);
        tx.setAmount(BigDecimal.TEN);
        tx.setCurrency("USD");
        tx.authorize(); // set to AUTHORIZED first

        when(eventRepo.existsByEventId("evt-1")).thenReturn(false);
        when(transactionRepo.findById(42L)).thenReturn(Optional.of(tx));

        service.process(payload, body, sig, ts);

        assertThat(tx.getStatus()).isEqualTo(PaymentStatus.CAPTURED.name());
        verify(eventRepo).save(any(PspWebhookEvent.class));
        verify(publisher).publish(any(PaymentEvent.class));
    }

    @Test
    void process_duplicateEventId_isNoOp() {
        long ts = Instant.now().getEpochSecond();
        PspWebhookPayload payload = new PspWebhookPayload("evt-dup", "PAYMENT_CAPTURED", 1L, "CAPTURED", BigDecimal.ONE, "USD");
        String body = "{}";
        String sig = sign(ts, body);

        when(eventRepo.existsByEventId("evt-dup")).thenReturn(true);

        service.process(payload, body, sig, ts);

        verify(transactionRepo, never()).findById(any());
        verify(publisher, never()).publish(any());
    }

    @Test
    void process_invalidSignature_throwsIllegalArgumentException() {
        long ts = Instant.now().getEpochSecond();
        PspWebhookPayload payload = new PspWebhookPayload("evt-x", "PAYMENT_CAPTURED", 1L, "CAPTURED", BigDecimal.ONE, "USD");

        assertThatThrownBy(() -> service.process(payload, "{}", "sha256=badhex", ts))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void process_staleTimestamp_throwsIllegalArgumentException() {
        long staleTs = Instant.now().getEpochSecond() - 400;
        PspWebhookPayload payload = new PspWebhookPayload("evt-y", "PAYMENT_CAPTURED", 1L, "CAPTURED", BigDecimal.ONE, "USD");
        String body = "{}";
        String sig = sign(staleTs, body);

        assertThatThrownBy(() -> service.process(payload, body, sig, staleTs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void process_unknownEventType_persistsAuditRowAndReturnsQuietly() {
        long ts = Instant.now().getEpochSecond();
        PspWebhookPayload payload = new PspWebhookPayload("evt-z", "UNKNOWN_TYPE", 1L, "WHATEVER", BigDecimal.ONE, "USD");
        String body = "{}";
        String sig = sign(ts, body);

        when(eventRepo.existsByEventId("evt-z")).thenReturn(false);

        service.process(payload, body, sig, ts);

        verify(eventRepo).save(any(PspWebhookEvent.class));
        verify(transactionRepo, never()).findById(any());
        verify(publisher, never()).publish(any());
    }

    @Test
    void process_voidEvent_updatesTransactionToVoided() {
        long ts = Instant.now().getEpochSecond();
        PspWebhookPayload payload = new PspWebhookPayload("evt-v", "PAYMENT_VOIDED", 7L, "VOIDED", BigDecimal.TEN, "USD");
        String body = "{}";
        String sig = sign(ts, body);

        Transaction tx = new Transaction();
        tx.setId(7L);
        tx.setMerchantId(1L);
        tx.setUserId(1L);
        tx.setAmount(BigDecimal.TEN);
        tx.setCurrency("USD");
        tx.authorize();

        when(eventRepo.existsByEventId("evt-v")).thenReturn(false);
        when(transactionRepo.findById(7L)).thenReturn(Optional.of(tx));

        service.process(payload, body, sig, ts);

        assertThat(tx.getStatus()).isEqualTo(PaymentStatus.VOIDED.name());
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```
./mvnw -pl payment-core -am test -Dtest=PspWebhookServiceTest
```

Expected: compile error — classes do not exist yet.

- [ ] **Step 3: Create `PspWebhookPayload`**

Create `payment-core/src/main/java/com/kimpay/payment/core/webhook/inbound/PspWebhookPayload.java`:

```java
package com.kimpay.payment.core.webhook.inbound;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public record PspWebhookPayload(
        @JsonProperty("event_id")     String eventId,
        @JsonProperty("event_type")   String eventType,
        @JsonProperty("transaction_id") Long transactionId,
        @JsonProperty("status")       String status,
        @JsonProperty("amount")       BigDecimal amount,
        @JsonProperty("currency")     String currency
) {}
```

- [ ] **Step 4: Create `PspWebhookService`**

Create `payment-core/src/main/java/com/kimpay/payment/core/webhook/inbound/PspWebhookService.java`:

```java
package com.kimpay.payment.core.webhook.inbound;

import com.kimpay.payment.core.event.PaymentEvent;
import com.kimpay.payment.core.event.PaymentEventPublisher;
import com.kimpay.payment.core.repository.PspWebhookEventRepository;
import com.kimpay.payment.core.repository.TransactionRepository;
import com.kimpay.payment.core.webhook.HmacSigningService;
import com.kimpay.payment.domain.entity.PspWebhookEvent;
import com.kimpay.payment.domain.entity.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
public class PspWebhookService {

    private final PspWebhookEventRepository eventRepo;
    private final TransactionRepository transactionRepo;
    private final PaymentEventPublisher publisher;
    private final HmacSigningService hmac;
    private final String webhookSecret;
    private final long toleranceSeconds;

    public PspWebhookService(
            PspWebhookEventRepository eventRepo,
            TransactionRepository transactionRepo,
            PaymentEventPublisher publisher,
            HmacSigningService hmac,
            @Value("${payment.webhook.psp-secret:}") String webhookSecret,
            @Value("${payment.security.timestamp-tolerance-seconds:300}") long toleranceSeconds
    ) {
        this.eventRepo = eventRepo;
        this.transactionRepo = transactionRepo;
        this.publisher = publisher;
        this.hmac = hmac;
        this.webhookSecret = webhookSecret;
        this.toleranceSeconds = toleranceSeconds;
    }

    @Transactional
    public void process(PspWebhookPayload payload, String rawBody, String signature, long timestampSeconds) {
        if (!hmac.verify(rawBody, timestampSeconds, signature, webhookSecret, toleranceSeconds)) {
            throw new IllegalArgumentException("Invalid or stale webhook signature");
        }

        if (eventRepo.existsByEventId(payload.eventId())) {
            log.debug("Duplicate PSP webhook event ignored: {}", payload.eventId());
            return;
        }

        switch (payload.eventType()) {
            case "PAYMENT_CAPTURED" -> reconcile(payload, tx -> tx.capture());
            case "PAYMENT_VOIDED"   -> reconcile(payload, tx -> tx.voidTransaction());
            case "PAYMENT_REFUNDED" -> reconcile(payload, tx -> tx.refund());
            default -> {
                log.warn("Unknown PSP webhook event type: {}", payload.eventType());
                persistAuditRow(payload, rawBody);
                return;
            }
        }

        persistAuditRow(payload, rawBody);
    }

    private void reconcile(PspWebhookPayload payload, java.util.function.Consumer<Transaction> transition) {
        Transaction tx = transactionRepo.findById(payload.transactionId())
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + payload.transactionId()));

        try {
            transition.accept(tx);
        } catch (IllegalStateException e) {
            log.warn("PSP webhook state conflict for txn {}: {}", payload.transactionId(), e.getMessage());
            persistAuditRow(payload, "conflict");
            return;
        }

        transactionRepo.save(tx);

        publisher.publish(new PaymentEvent(
                payload.eventType(),
                tx.getId(),
                tx.getUserId(),
                tx.getMerchantId(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getStatus(),
                "Reconciled via PSP webhook",
                LocalDateTime.now()
        ));
    }

    private void persistAuditRow(PspWebhookPayload payload, String rawBody) {
        PspWebhookEvent event = new PspWebhookEvent();
        event.setEventId(payload.eventId());
        event.setEventType(payload.eventType());
        event.setPayload(rawBody);
        event.setProcessedAt(LocalDateTime.now());
        eventRepo.save(event);
    }
}
```

- [ ] **Step 5: Run tests to confirm they pass**

```
./mvnw -pl payment-core -am test -Dtest=PspWebhookServiceTest
```

Expected: all 6 tests pass.

- [ ] **Step 6: Commit**

```
git add payment-core/src/main/java/com/kimpay/payment/core/webhook/inbound/ \
        payment-core/src/test/java/com/kimpay/payment/core/webhook/inbound/
git commit -m "feat(webhook): PspWebhookPayload + PspWebhookService with HMAC verify and state reconciliation"
```

---

## Task 6: `PspWebhookController` and SecurityConfig update

**Files:**
- Create: `payment-api/src/main/java/com/kimpay/payment/controller/PspWebhookController.java`
- Modify: `payment-api/src/main/java/com/kimpay/payment/security/SecurityConfig.java`

- [ ] **Step 1: Create the controller**

Create `payment-api/src/main/java/com/kimpay/payment/controller/PspWebhookController.java`:

```java
package com.kimpay.payment.controller;

import com.kimpay.payment.core.webhook.inbound.PspWebhookPayload;
import com.kimpay.payment.core.webhook.inbound.PspWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
public class PspWebhookController {

    private final PspWebhookService pspWebhookService;

    @PostMapping("/psp")
    public ResponseEntity<Void> receivePspWebhook(
            @RequestHeader("X-Kimpay-Signature")  String signature,
            @RequestHeader("X-Kimpay-Timestamp")  long timestamp,
            @RequestBody String rawBody,
            @org.springframework.web.bind.annotation.RequestBody(required = false)
            PspWebhookPayload payload
    ) {
        // rawBody and payload are both parsed — use Jackson manually to avoid double-read.
        // We inject ObjectMapper and parse once.
        return ResponseEntity.ok().build();
    }
}
```

Wait — reading the raw body AND binding to a record simultaneously requires a custom approach. Replace the controller with the correct pattern using `HttpServletRequest`:

```java
package com.kimpay.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kimpay.payment.core.webhook.inbound.PspWebhookPayload;
import com.kimpay.payment.core.webhook.inbound.PspWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
public class PspWebhookController {

    private final PspWebhookService pspWebhookService;
    private final ObjectMapper objectMapper;

    @PostMapping("/psp")
    public ResponseEntity<Void> receivePspWebhook(
            @RequestHeader("X-Kimpay-Signature") String signature,
            @RequestHeader("X-Kimpay-Timestamp") long timestamp,
            @RequestBody String rawBody
    ) throws Exception {
        PspWebhookPayload payload = objectMapper.readValue(rawBody, PspWebhookPayload.class);
        pspWebhookService.process(payload, rawBody, signature, timestamp);
        return ResponseEntity.ok().build();
    }
}
```

- [ ] **Step 2: Permit `/api/webhooks/psp` in SecurityConfig**

In `payment-api/src/main/java/com/kimpay/payment/security/SecurityConfig.java`, change:

```java
.requestMatchers("/actuator/health", "/actuator/info").permitAll()
```

to:

```java
.requestMatchers("/actuator/health", "/actuator/info", "/api/webhooks/psp").permitAll()
```

- [ ] **Step 3: Add `payment.webhook.psp-secret` to test properties**

In `payment-api/src/test/resources/application-test.yml`, add under the `payment:` block:

```yaml
  webhook:
    psp-secret: test-psp-webhook-secret
```

- [ ] **Step 4: Commit**

```
git add payment-api/src/main/java/com/kimpay/payment/controller/PspWebhookController.java \
        payment-api/src/main/java/com/kimpay/payment/security/SecurityConfig.java \
        payment-api/src/test/resources/application-test.yml
git commit -m "feat(webhook): PspWebhookController + permit /api/webhooks/psp in SecurityConfig"
```

---

## Task 7: Inbound webhook integration test

**Files:**
- Create: `payment-api/src/test/java/com/kimpay/payment/PspWebhookIntegrationTest.java`

- [ ] **Step 1: Write the integration test**

Create `payment-api/src/test/java/com/kimpay/payment/PspWebhookIntegrationTest.java`:

```java
package com.kimpay.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kimpay.payment.core.repository.PspWebhookEventRepository;
import com.kimpay.payment.core.repository.TransactionRepository;
import com.kimpay.payment.core.webhook.HmacSigningService;
import com.kimpay.payment.domain.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "payment.encryption.key-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
        "payment.webhook.psp-secret=test-psp-webhook-secret"
})
class PspWebhookIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TransactionRepository transactionRepository;
    @Autowired PspWebhookEventRepository webhookEventRepository;

    HmacSigningService hmac = new HmacSigningService();
    private static final String SECRET = "test-psp-webhook-secret";

    Transaction savedTx;

    @BeforeEach
    void setUp() {
        webhookEventRepository.deleteAll();
        transactionRepository.deleteAll();

        Transaction tx = new Transaction();
        tx.setUserId(1L);
        tx.setMerchantId(1L);
        tx.setAmount(new BigDecimal("50.00"));
        tx.setCurrency("USD");
        tx.authorize();
        savedTx = transactionRepository.save(tx);
    }

    @Test
    void validCapture_updatesTransactionAndPersistsEvent() throws Exception {
        long ts = Instant.now().getEpochSecond();
        String body = objectMapper.writeValueAsString(Map.of(
                "event_id", "evt-cap-1",
                "event_type", "PAYMENT_CAPTURED",
                "transaction_id", savedTx.getId(),
                "status", "CAPTURED",
                "amount", "50.00",
                "currency", "USD"
        ));
        String sig = "sha256=" + hmac.sign(ts + "." + body, SECRET);

        mockMvc.perform(post("/api/webhooks/psp")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Kimpay-Signature", sig)
                .header("X-Kimpay-Timestamp", ts)
                .content(body))
                .andExpect(status().isOk());

        Transaction updated = transactionRepository.findById(savedTx.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(PaymentStatus.CAPTURED.name());
        assertThat(webhookEventRepository.existsByEventId("evt-cap-1")).isTrue();
    }

    @Test
    void duplicateEventId_isNoOpAndReturns200() throws Exception {
        long ts = Instant.now().getEpochSecond();
        String body = objectMapper.writeValueAsString(Map.of(
                "event_id", "evt-dup",
                "event_type", "PAYMENT_CAPTURED",
                "transaction_id", savedTx.getId(),
                "status", "CAPTURED",
                "amount", "50.00",
                "currency", "USD"
        ));
        String sig = "sha256=" + hmac.sign(ts + "." + body, SECRET);

        mockMvc.perform(post("/api/webhooks/psp")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Kimpay-Signature", sig)
                .header("X-Kimpay-Timestamp", ts)
                .content(body))
                .andExpect(status().isOk());

        // Second call — same event_id
        mockMvc.perform(post("/api/webhooks/psp")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Kimpay-Signature", sig)
                .header("X-Kimpay-Timestamp", ts)
                .content(body))
                .andExpect(status().isOk());

        // Still CAPTURED, not double-applied
        assertThat(transactionRepository.findById(savedTx.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CAPTURED.name());
    }

    @Test
    void invalidSignature_returns400() throws Exception {
        long ts = Instant.now().getEpochSecond();
        String body = """
                {"event_id":"evt-bad","event_type":"PAYMENT_CAPTURED","transaction_id":1,"status":"CAPTURED","amount":"10","currency":"USD"}
                """.strip();

        mockMvc.perform(post("/api/webhooks/psp")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Kimpay-Signature", "sha256=badhex")
                .header("X-Kimpay-Timestamp", ts)
                .content(body))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Wire `IllegalArgumentException` → 400 in the exception handler**

Check `payment-api/src/main/java/com/kimpay/payment/controller/ApiExceptionHandler.java`. If it already maps `IllegalArgumentException` to 400, no change needed. If not, add:

```java
@ExceptionHandler(IllegalArgumentException.class)
public ResponseEntity<ErrorResponse> handleBadArgument(IllegalArgumentException ex) {
    return ResponseEntity.badRequest()
            .body(new ErrorResponse(ErrorCode.VALIDATION_ERROR.getCode(), ex.getMessage()));
}
```

(Check `ErrorCode` enum in `payment-common` for the right constant — use whichever existing code maps to 400.)

- [ ] **Step 3: Run integration tests**

```
./mvnw -pl payment-api -am test -Dtest=PspWebhookIntegrationTest
```

Expected: all 3 tests pass.

- [ ] **Step 4: Commit**

```
git add payment-api/src/test/java/com/kimpay/payment/PspWebhookIntegrationTest.java
git commit -m "test(webhook): inbound PSP webhook integration tests"
```

---

## Task 8: Flyway V7 — `webhook_endpoints` + `webhook_deliveries` tables

**Files:**
- Create: `payment-api/src/main/resources/db/migration/V7__webhook_tables.sql`

- [ ] **Step 1: Create migration**

Create `payment-api/src/main/resources/db/migration/V7__webhook_tables.sql`:

```sql
CREATE TABLE webhook_endpoints (
    id          BIGSERIAL    PRIMARY KEY,
    merchant_id BIGINT       NOT NULL REFERENCES merchants(id),
    url         VARCHAR(2048) NOT NULL,
    secret      VARCHAR(500) NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL
);

CREATE INDEX idx_webhook_endpoints_merchant ON webhook_endpoints(merchant_id);

CREATE TABLE webhook_deliveries (
    id            BIGSERIAL    PRIMARY KEY,
    endpoint_id   BIGINT       NOT NULL REFERENCES webhook_endpoints(id),
    event_type    VARCHAR(100) NOT NULL,
    payload       TEXT         NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    attempts      INT          NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP,
    last_response VARCHAR(1000),
    created_at    TIMESTAMP    NOT NULL
);

CREATE INDEX idx_webhook_deliveries_pending
    ON webhook_deliveries(status, next_retry_at)
    WHERE status IN ('PENDING', 'FAILED');
```

- [ ] **Step 2: Commit**

```
git add payment-api/src/main/resources/db/migration/V7__webhook_tables.sql
git commit -m "feat(webhook): Flyway V7 — webhook_endpoints + webhook_deliveries tables"
```

---

## Task 9: `WebhookDeliveryStatus`, `WebhookEndpoint`, and `WebhookDelivery` entities

**Files:**
- Create: `payment-domain/src/main/java/com/kimpay/payment/domain/entity/WebhookDeliveryStatus.java`
- Create: `payment-domain/src/main/java/com/kimpay/payment/domain/entity/WebhookEndpoint.java`
- Create: `payment-domain/src/main/java/com/kimpay/payment/domain/entity/WebhookDelivery.java`

- [ ] **Step 1: Create the status enum**

Create `payment-domain/src/main/java/com/kimpay/payment/domain/entity/WebhookDeliveryStatus.java`:

```java
package com.kimpay.payment.domain.entity;

public enum WebhookDeliveryStatus {
    PENDING, SUCCESS, FAILED, DEAD
}
```

- [ ] **Step 2: Create `WebhookEndpoint`**

Create `payment-domain/src/main/java/com/kimpay/payment/domain/entity/WebhookEndpoint.java`:

```java
package com.kimpay.payment.domain.entity;

import com.kimpay.payment.security.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@Table(name = "webhook_endpoints")
public class WebhookEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "url", nullable = false, length = 2048)
    private String url;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "secret", nullable = false, length = 500)
    private String secret;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 3: Create `WebhookDelivery`**

Create `payment-domain/src/main/java/com/kimpay/payment/domain/entity/WebhookDelivery.java`:

```java
package com.kimpay.payment.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@Table(name = "webhook_deliveries")
public class WebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "endpoint_id", nullable = false)
    private Long endpointId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "last_response", length = 1000)
    private String lastResponse;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 4: Commit**

```
git add payment-domain/src/main/java/com/kimpay/payment/domain/entity/WebhookDeliveryStatus.java \
        payment-domain/src/main/java/com/kimpay/payment/domain/entity/WebhookEndpoint.java \
        payment-domain/src/main/java/com/kimpay/payment/domain/entity/WebhookDelivery.java
git commit -m "feat(webhook): WebhookEndpoint, WebhookDelivery, WebhookDeliveryStatus entities"
```

---

## Task 10: Repositories for outbound webhooks

**Files:**
- Create: `payment-core/src/main/java/com/kimpay/payment/core/repository/WebhookEndpointRepository.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/repository/WebhookDeliveryRepository.java`

- [ ] **Step 1: Create `WebhookEndpointRepository`**

Create `payment-core/src/main/java/com/kimpay/payment/core/repository/WebhookEndpointRepository.java`:

```java
package com.kimpay.payment.core.repository;

import com.kimpay.payment.domain.entity.WebhookEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, Long> {
    List<WebhookEndpoint> findByMerchantIdAndEnabledTrue(Long merchantId);
    List<WebhookEndpoint> findByMerchantId(Long merchantId);
}
```

- [ ] **Step 2: Create `WebhookDeliveryRepository`**

Create `payment-core/src/main/java/com/kimpay/payment/core/repository/WebhookDeliveryRepository.java`:

```java
package com.kimpay.payment.core.repository;

import com.kimpay.payment.domain.entity.WebhookDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    @Query("""
        SELECT d FROM WebhookDelivery d
        WHERE d.status IN ('PENDING', 'FAILED')
        AND (d.nextRetryAt IS NULL OR d.nextRetryAt <= :now)
        ORDER BY d.nextRetryAt ASC NULLS FIRST
        LIMIT :limit
    """)
    List<WebhookDelivery> findDueForRetry(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
```

- [ ] **Step 3: Commit**

```
git add payment-core/src/main/java/com/kimpay/payment/core/repository/WebhookEndpointRepository.java \
        payment-core/src/main/java/com/kimpay/payment/core/repository/WebhookDeliveryRepository.java
git commit -m "feat(webhook): WebhookEndpointRepository and WebhookDeliveryRepository"
```

---

## Task 11: `WebhookDispatchService` with unit tests

**Files:**
- Create: `payment-core/src/main/java/com/kimpay/payment/core/webhook/outbound/WebhookDispatchService.java`
- Create: `payment-core/src/test/java/com/kimpay/payment/core/webhook/outbound/WebhookDispatchServiceTest.java`

- [ ] **Step 1: Write the failing unit test**

Create `payment-core/src/test/java/com/kimpay/payment/core/webhook/outbound/WebhookDispatchServiceTest.java`:

```java
package com.kimpay.payment.core.webhook.outbound;

import com.kimpay.payment.core.repository.WebhookDeliveryRepository;
import com.kimpay.payment.core.repository.WebhookEndpointRepository;
import com.kimpay.payment.core.webhook.HmacSigningService;
import com.kimpay.payment.domain.entity.WebhookDelivery;
import com.kimpay.payment.domain.entity.WebhookDeliveryStatus;
import com.kimpay.payment.domain.entity.WebhookEndpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookDispatchServiceTest {

    @Mock WebhookDeliveryRepository deliveryRepo;
    @Mock WebhookEndpointRepository endpointRepo;
    @Mock RestClient restClient;
    @Mock RestClient.RequestBodyUriSpec requestSpec;
    @Mock RestClient.RequestBodySpec bodySpec;
    @Mock RestClient.ResponseSpec responseSpec;

    HmacSigningService hmac = new HmacSigningService();
    WebhookDispatchService service;

    @BeforeEach
    void setUp() {
        service = new WebhookDispatchService(deliveryRepo, endpointRepo, hmac, restClient);
    }

    private WebhookEndpoint endpoint(String url, String secret) {
        WebhookEndpoint ep = new WebhookEndpoint();
        ep.setId(1L);
        ep.setMerchantId(10L);
        ep.setUrl(url);
        ep.setSecret(secret);
        ep.setEnabled(true);
        return ep;
    }

    private WebhookDelivery delivery(long endpointId, int attempts) {
        WebhookDelivery d = new WebhookDelivery();
        d.setId(100L);
        d.setEndpointId(endpointId);
        d.setEventType("PAYMENT_CAPTURED");
        d.setPayload("{\"event\":\"PAYMENT_CAPTURED\"}");
        d.setStatus(WebhookDeliveryStatus.PENDING.name());
        d.setAttempts(attempts);
        d.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        return d;
    }

    @Test
    void dispatch_successResponse_marksDeliverySuccess() {
        WebhookEndpoint ep = endpoint("https://merchant.example.com/hooks", "secret");
        WebhookDelivery del = delivery(1L, 0);

        when(endpointRepo.findById(1L)).thenReturn(Optional.of(ep));
        when(restClient.post()).thenReturn(requestSpec);
        when(requestSpec.uri("https://merchant.example.com/hooks")).thenReturn(bodySpec);
        when(bodySpec.header(any(), any())).thenReturn(bodySpec);
        when(bodySpec.contentType(any())).thenReturn(bodySpec);
        when(bodySpec.body(any(String.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        doNothing().when(responseSpec).toBodilessEntity();

        service.dispatch(del);

        ArgumentCaptor<WebhookDelivery> cap = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(WebhookDeliveryStatus.SUCCESS.name());
        assertThat(cap.getValue().getAttempts()).isEqualTo(1);
    }

    @Test
    void dispatch_httpError_incrementsAttemptsAndSchedulesRetry() {
        WebhookEndpoint ep = endpoint("https://merchant.example.com/hooks", "secret");
        WebhookDelivery del = delivery(1L, 0);

        when(endpointRepo.findById(1L)).thenReturn(Optional.of(ep));
        when(restClient.post()).thenReturn(requestSpec);
        when(requestSpec.uri(any(String.class))).thenReturn(bodySpec);
        when(bodySpec.header(any(), any())).thenReturn(bodySpec);
        when(bodySpec.contentType(any())).thenReturn(bodySpec);
        when(bodySpec.body(any(String.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        doThrow(new org.springframework.web.client.HttpClientErrorException(
                org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR))
                .when(responseSpec).toBodilessEntity();

        service.dispatch(del);

        ArgumentCaptor<WebhookDelivery> cap = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED.name());
        assertThat(cap.getValue().getAttempts()).isEqualTo(1);
        assertThat(cap.getValue().getNextRetryAt()).isAfter(LocalDateTime.now());
    }

    @Test
    void dispatch_maxAttemptsReached_marksDeliveryDead() {
        WebhookEndpoint ep = endpoint("https://merchant.example.com/hooks", "secret");
        WebhookDelivery del = delivery(1L, 4); // already at 4, next failure → 5 → DEAD

        when(endpointRepo.findById(1L)).thenReturn(Optional.of(ep));
        when(restClient.post()).thenReturn(requestSpec);
        when(requestSpec.uri(any(String.class))).thenReturn(bodySpec);
        when(bodySpec.header(any(), any())).thenReturn(bodySpec);
        when(bodySpec.contentType(any())).thenReturn(bodySpec);
        when(bodySpec.body(any(String.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        doThrow(new RuntimeException("timeout")).when(responseSpec).toBodilessEntity();

        service.dispatch(del);

        ArgumentCaptor<WebhookDelivery> cap = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(WebhookDeliveryStatus.DEAD.name());
        assertThat(cap.getValue().getAttempts()).isEqualTo(5);
    }

    @Test
    void retryDelaySeconds_followsExponentialSchedule() {
        assertThat(WebhookDispatchService.retryDelaySeconds(1)).isEqualTo(30);
        assertThat(WebhookDispatchService.retryDelaySeconds(2)).isEqualTo(300);
        assertThat(WebhookDispatchService.retryDelaySeconds(3)).isEqualTo(1800);
        assertThat(WebhookDispatchService.retryDelaySeconds(4)).isEqualTo(7200);
        assertThat(WebhookDispatchService.retryDelaySeconds(5)).isEqualTo(28800);
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```
./mvnw -pl payment-core -am test -Dtest=WebhookDispatchServiceTest
```

Expected: compile error — `WebhookDispatchService` does not exist.

- [ ] **Step 3: Create `WebhookDispatchService`**

Create `payment-core/src/main/java/com/kimpay/payment/core/webhook/outbound/WebhookDispatchService.java`:

```java
package com.kimpay.payment.core.webhook.outbound;

import com.kimpay.payment.core.repository.WebhookDeliveryRepository;
import com.kimpay.payment.core.repository.WebhookEndpointRepository;
import com.kimpay.payment.core.webhook.HmacSigningService;
import com.kimpay.payment.domain.entity.WebhookDelivery;
import com.kimpay.payment.domain.entity.WebhookDeliveryStatus;
import com.kimpay.payment.domain.entity.WebhookEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookDispatchService {

    private static final int MAX_ATTEMPTS = 5;

    private final WebhookDeliveryRepository deliveryRepo;
    private final WebhookEndpointRepository endpointRepo;
    private final HmacSigningService hmac;
    private final RestClient restClient;

    public void dispatch(WebhookDelivery delivery) {
        WebhookEndpoint endpoint = endpointRepo.findById(delivery.getEndpointId())
                .orElseThrow(() -> new IllegalStateException("Endpoint not found: " + delivery.getEndpointId()));

        long ts = Instant.now().getEpochSecond();
        String signature = "sha256=" + hmac.sign(ts + "." + delivery.getPayload(), endpoint.getSecret());

        try {
            restClient.post()
                    .uri(endpoint.getUrl())
                    .header("X-Kimpay-Signature", signature)
                    .header("X-Kimpay-Timestamp", String.valueOf(ts))
                    .header("X-Kimpay-Event", delivery.getEventType())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(delivery.getPayload())
                    .retrieve()
                    .toBodilessEntity();

            delivery.setAttempts(delivery.getAttempts() + 1);
            delivery.setStatus(WebhookDeliveryStatus.SUCCESS.name());
            delivery.setLastResponse("200 OK");
            log.info("Webhook delivered: delivery={} endpoint={}", delivery.getId(), endpoint.getUrl());

        } catch (Exception e) {
            int newAttempts = delivery.getAttempts() + 1;
            delivery.setAttempts(newAttempts);
            delivery.setLastResponse(e.getMessage() != null ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 1000)) : "error");

            if (newAttempts >= MAX_ATTEMPTS) {
                delivery.setStatus(WebhookDeliveryStatus.DEAD.name());
                log.warn("Webhook delivery dead after {} attempts: delivery={}", newAttempts, delivery.getId());
            } else {
                delivery.setStatus(WebhookDeliveryStatus.FAILED.name());
                delivery.setNextRetryAt(LocalDateTime.now().plusSeconds(retryDelaySeconds(newAttempts)));
                log.warn("Webhook delivery failed (attempt {}), will retry: delivery={}", newAttempts, delivery.getId());
            }
        }

        deliveryRepo.save(delivery);
    }

    /** Exponential backoff: 30s, 5m, 30m, 2h, 8h. */
    public static long retryDelaySeconds(int attemptNumber) {
        return switch (attemptNumber) {
            case 1 -> 30;
            case 2 -> 300;
            case 3 -> 1800;
            case 4 -> 7200;
            default -> 28800;
        };
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```
./mvnw -pl payment-core -am test -Dtest=WebhookDispatchServiceTest
```

Expected: all 4 tests pass.

- [ ] **Step 5: Commit**

```
git add payment-core/src/main/java/com/kimpay/payment/core/webhook/outbound/WebhookDispatchService.java \
        payment-core/src/test/java/com/kimpay/payment/core/webhook/outbound/WebhookDispatchServiceTest.java
git commit -m "feat(webhook): WebhookDispatchService with exponential backoff and dead-letter"
```

---

## Task 12: `WebhookRetryScheduler`

**Files:**
- Create: `payment-core/src/main/java/com/kimpay/payment/core/webhook/outbound/WebhookRetryScheduler.java`

- [ ] **Step 1: Create the scheduler**

Create `payment-core/src/main/java/com/kimpay/payment/core/webhook/outbound/WebhookRetryScheduler.java`:

```java
package com.kimpay.payment.core.webhook.outbound;

import com.kimpay.payment.core.repository.WebhookDeliveryRepository;
import com.kimpay.payment.domain.entity.WebhookDelivery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookRetryScheduler {

    private static final int BATCH_SIZE = 50;

    private final WebhookDeliveryRepository deliveryRepo;
    private final WebhookDispatchService dispatchService;

    @Scheduled(fixedDelay = 10_000)
    public void processDueDeliveries() {
        List<WebhookDelivery> due = deliveryRepo.findDueForRetry(LocalDateTime.now(), BATCH_SIZE);
        if (due.isEmpty()) return;

        log.debug("Processing {} due webhook deliveries", due.size());
        for (WebhookDelivery delivery : due) {
            try {
                dispatchService.dispatch(delivery);
            } catch (Exception e) {
                log.error("Unexpected error dispatching webhook delivery {}: {}", delivery.getId(), e.getMessage());
            }
        }
    }
}
```

- [ ] **Step 2: Enable scheduling in the application**

Check `payment-api/src/main/java/com/kimpay/payment/PaymentApplication.java`. Add `@EnableScheduling` if not present:

```java
@SpringBootApplication
@EnableScheduling
public class PaymentApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
```

Import: `org.springframework.scheduling.annotation.EnableScheduling`

- [ ] **Step 3: Commit**

```
git add payment-core/src/main/java/com/kimpay/payment/core/webhook/outbound/WebhookRetryScheduler.java \
        payment-api/src/main/java/com/kimpay/payment/PaymentApplication.java
git commit -m "feat(webhook): WebhookRetryScheduler polling due deliveries every 10s"
```

---

## Task 13: Wire `PaymentEventListener` to create `WebhookDelivery` rows

**Files:**
- Modify: `payment-api/src/main/java/com/kimpay/payment/event/PaymentEventListener.java`

The existing listener logs events. We extend it to also fan out to each merchant's enabled webhook endpoints.

- [ ] **Step 1: Update `PaymentEventListener`**

Replace `payment-api/src/main/java/com/kimpay/payment/event/PaymentEventListener.java` with:

```java
package com.kimpay.payment.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kimpay.payment.core.event.PaymentEvent;
import com.kimpay.payment.core.repository.WebhookDeliveryRepository;
import com.kimpay.payment.core.repository.WebhookEndpointRepository;
import com.kimpay.payment.domain.entity.WebhookDelivery;
import com.kimpay.payment.domain.entity.WebhookDeliveryStatus;
import com.kimpay.payment.domain.entity.WebhookEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentEventListener {

    private final ObjectMapper objectMapper;
    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryRepository deliveryRepository;

    @KafkaListener(topics = "${payment.kafka.payment-topic}", groupId = "payment-log-group")
    public void consumePaymentEvent(String message) {
        try {
            PaymentEvent event = objectMapper.readValue(message, PaymentEvent.class);
            log.info("Received Payment Event: Type={}, TxID={}, Amount={} {}",
                    event.eventType(), event.transactionId(), event.amount(), event.currency());
            createWebhookDeliveries(event, message);
        } catch (JsonProcessingException e) {
            log.error("Error deserializing payment event from Kafka", e);
        }
    }

    private void createWebhookDeliveries(PaymentEvent event, String rawPayload) {
        List<WebhookEndpoint> endpoints = endpointRepository
                .findByMerchantIdAndEnabledTrue(event.merchantId());
        for (WebhookEndpoint ep : endpoints) {
            WebhookDelivery delivery = new WebhookDelivery();
            delivery.setEndpointId(ep.getId());
            delivery.setEventType(event.eventType());
            delivery.setPayload(rawPayload);
            delivery.setStatus(WebhookDeliveryStatus.PENDING.name());
            delivery.setNextRetryAt(LocalDateTime.now());
            deliveryRepository.save(delivery);
        }
    }
}
```

- [ ] **Step 2: Commit**

```
git add payment-api/src/main/java/com/kimpay/payment/event/PaymentEventListener.java
git commit -m "feat(webhook): wire PaymentEventListener to create WebhookDelivery rows on payment events"
```

---

## Task 14: Webhook endpoint registration API

**Files:**
- Create: `payment-core/src/main/java/com/kimpay/payment/core/dto/RegisterWebhookEndpointRequest.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/service/WebhookEndpointService.java`
- Create: `payment-api/src/main/java/com/kimpay/payment/controller/WebhookEndpointController.java`

- [ ] **Step 1: Create the request DTO**

Create `payment-core/src/main/java/com/kimpay/payment/core/dto/RegisterWebhookEndpointRequest.java`:

```java
package com.kimpay.payment.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RegisterWebhookEndpointRequest(
        @NotBlank
        @Pattern(regexp = "https://.*", message = "URL must use HTTPS")
        String url
) {}
```

- [ ] **Step 2: Create `WebhookEndpointService`**

Create `payment-core/src/main/java/com/kimpay/payment/core/service/WebhookEndpointService.java`:

```java
package com.kimpay.payment.core.service;

import com.kimpay.payment.core.dto.RegisterWebhookEndpointRequest;
import com.kimpay.payment.core.repository.WebhookEndpointRepository;
import com.kimpay.payment.domain.entity.WebhookEndpoint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WebhookEndpointService {

    private final WebhookEndpointRepository endpointRepository;

    @Transactional
    public WebhookEndpointCreated register(Long merchantId, RegisterWebhookEndpointRequest request) {
        String secret = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");

        WebhookEndpoint ep = new WebhookEndpoint();
        ep.setMerchantId(merchantId);
        ep.setUrl(request.url());
        ep.setSecret(secret);
        ep.setEnabled(true);
        endpointRepository.save(ep);

        return new WebhookEndpointCreated(ep.getId(), ep.getUrl(), secret);
    }

    @Transactional(readOnly = true)
    public List<WebhookEndpoint> list(Long merchantId) {
        return endpointRepository.findByMerchantId(merchantId);
    }

    @Transactional
    public void delete(Long merchantId, Long endpointId) {
        WebhookEndpoint ep = endpointRepository.findById(endpointId)
                .orElseThrow(() -> new IllegalArgumentException("Endpoint not found: " + endpointId));
        if (!ep.getMerchantId().equals(merchantId)) {
            throw new com.kimpay.payment.exception.ResourceAccessDeniedException("Not your endpoint");
        }
        endpointRepository.delete(ep);
    }

    public record WebhookEndpointCreated(Long id, String url, String secret) {}
}
```

- [ ] **Step 3: Create `WebhookEndpointController`**

Create `payment-api/src/main/java/com/kimpay/payment/controller/WebhookEndpointController.java`:

```java
package com.kimpay.payment.controller;

import com.kimpay.payment.core.dto.RegisterWebhookEndpointRequest;
import com.kimpay.payment.core.service.WebhookEndpointService;
import com.kimpay.payment.security.MerchantPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/webhook-endpoints")
@RequiredArgsConstructor
public class WebhookEndpointController {

    private final WebhookEndpointService endpointService;

    @PostMapping
    public ResponseEntity<WebhookEndpointService.WebhookEndpointCreated> register(
            @AuthenticationPrincipal MerchantPrincipal principal,
            @Valid @RequestBody RegisterWebhookEndpointRequest request
    ) {
        WebhookEndpointService.WebhookEndpointCreated created =
                endpointService.register(principal.merchantId(), request);
        return ResponseEntity.status(201).body(created);
    }

    @GetMapping
    public ResponseEntity<List<?>> list(@AuthenticationPrincipal MerchantPrincipal principal) {
        return ResponseEntity.ok(endpointService.list(principal.merchantId()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal MerchantPrincipal principal,
            @PathVariable Long id
    ) {
        endpointService.delete(principal.merchantId(), id);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 4: Wire `RestClient` bean**

`WebhookDispatchService` needs a `RestClient`. Add a `@Bean` in `payment-api`. Check if any `RestClient` bean already exists:

```
grep -r "RestClient" payment-api/src/main/java --include="*.java" -l
```

If none found, create `payment-api/src/main/java/com/kimpay/payment/config/RestClientConfig.java`:

```java
package com.kimpay.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient webhookRestClient() {
        return RestClient.builder()
                .build();
    }
}
```

- [ ] **Step 5: Commit**

```
git add payment-core/src/main/java/com/kimpay/payment/core/dto/RegisterWebhookEndpointRequest.java \
        payment-core/src/main/java/com/kimpay/payment/core/service/WebhookEndpointService.java \
        payment-api/src/main/java/com/kimpay/payment/controller/WebhookEndpointController.java \
        payment-api/src/main/java/com/kimpay/payment/config/RestClientConfig.java
git commit -m "feat(webhook): merchant webhook endpoint registration API"
```

---

## Task 15: Outbound webhook delivery integration test

**Files:**
- Create: `payment-api/src/test/java/com/kimpay/payment/WebhookDeliveryIntegrationTest.java`

- [ ] **Step 1: Write the integration test**

Create `payment-api/src/test/java/com/kimpay/payment/WebhookDeliveryIntegrationTest.java`:

```java
package com.kimpay.payment;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.kimpay.payment.core.repository.WebhookDeliveryRepository;
import com.kimpay.payment.core.repository.WebhookEndpointRepository;
import com.kimpay.payment.core.webhook.outbound.WebhookDispatchService;
import com.kimpay.payment.domain.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "payment.encryption.key-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
        "payment.webhook.psp-secret=test-psp-webhook-secret"
})
class WebhookDeliveryIntegrationTest {

    static WireMockServer wireMock = new WireMockServer(wireMockConfig().dynamicPort());

    @BeforeAll
    static void startWireMock() { wireMock.start(); }

    @AfterAll
    static void stopWireMock() { wireMock.stop(); }

    @BeforeEach
    void resetWireMock() { wireMock.resetAll(); }

    @Autowired WebhookEndpointRepository endpointRepo;
    @Autowired WebhookDeliveryRepository deliveryRepo;
    @Autowired WebhookDispatchService dispatchService;

    @BeforeEach
    void setUp() {
        deliveryRepo.deleteAll();
        endpointRepo.deleteAll();
    }

    @Test
    void dispatch_successfulDelivery_marksSuccess() {
        wireMock.stubFor(post(urlEqualTo("/hooks"))
                .willReturn(aResponse().withStatus(200)));

        WebhookEndpoint ep = new WebhookEndpoint();
        ep.setMerchantId(1L);
        ep.setUrl("http://localhost:" + wireMock.port() + "/hooks");
        ep.setSecret("test-endpoint-secret");
        ep.setEnabled(true);
        endpointRepo.save(ep);

        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setEndpointId(ep.getId());
        delivery.setEventType("PAYMENT_CAPTURED");
        delivery.setPayload("{\"event\":\"PAYMENT_CAPTURED\"}");
        delivery.setStatus(WebhookDeliveryStatus.PENDING.name());
        delivery.setAttempts(0);
        delivery.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        deliveryRepo.save(delivery);

        dispatchService.dispatch(delivery);

        WebhookDelivery updated = deliveryRepo.findById(delivery.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(WebhookDeliveryStatus.SUCCESS.name());
        assertThat(updated.getAttempts()).isEqualTo(1);

        wireMock.verify(postRequestedFor(urlEqualTo("/hooks"))
                .withHeader("X-Kimpay-Signature", matching("sha256=[0-9a-f]{64}"))
                .withHeader("X-Kimpay-Timestamp", matching("\\d+")));
    }

    @Test
    void dispatch_serverError_marksFailedAndSchedulesRetry() {
        wireMock.stubFor(post(urlEqualTo("/hooks"))
                .willReturn(aResponse().withStatus(500)));

        WebhookEndpoint ep = new WebhookEndpoint();
        ep.setMerchantId(1L);
        ep.setUrl("http://localhost:" + wireMock.port() + "/hooks");
        ep.setSecret("test-endpoint-secret");
        ep.setEnabled(true);
        endpointRepo.save(ep);

        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setEndpointId(ep.getId());
        delivery.setEventType("PAYMENT_CAPTURED");
        delivery.setPayload("{\"event\":\"PAYMENT_CAPTURED\"}");
        delivery.setStatus(WebhookDeliveryStatus.PENDING.name());
        delivery.setAttempts(0);
        delivery.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        deliveryRepo.save(delivery);

        dispatchService.dispatch(delivery);

        WebhookDelivery updated = deliveryRepo.findById(delivery.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED.name());
        assertThat(updated.getAttempts()).isEqualTo(1);
        assertThat(updated.getNextRetryAt()).isAfter(LocalDateTime.now());
    }

    @Test
    void dispatch_maxAttemptsExceeded_marksDeliveryDead() {
        wireMock.stubFor(post(urlEqualTo("/hooks"))
                .willReturn(aResponse().withStatus(500)));

        WebhookEndpoint ep = new WebhookEndpoint();
        ep.setMerchantId(1L);
        ep.setUrl("http://localhost:" + wireMock.port() + "/hooks");
        ep.setSecret("test-endpoint-secret");
        ep.setEnabled(true);
        endpointRepo.save(ep);

        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setEndpointId(ep.getId());
        delivery.setEventType("PAYMENT_CAPTURED");
        delivery.setPayload("{\"event\":\"PAYMENT_CAPTURED\"}");
        delivery.setStatus(WebhookDeliveryStatus.FAILED.name());
        delivery.setAttempts(4); // one more failure → DEAD
        delivery.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        deliveryRepo.save(delivery);

        dispatchService.dispatch(delivery);

        WebhookDelivery updated = deliveryRepo.findById(delivery.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(WebhookDeliveryStatus.DEAD.name());
        assertThat(updated.getAttempts()).isEqualTo(5);
    }
}
```

- [ ] **Step 2: Run all tests**

```
./mvnw test
```

Expected: `BUILD SUCCESS` with all tests passing.

- [ ] **Step 3: Commit**

```
git add payment-api/src/test/java/com/kimpay/payment/WebhookDeliveryIntegrationTest.java
git commit -m "test(webhook): outbound delivery integration tests with WireMock"
```

---

## Task 16: Run full test suite and verify

- [ ] **Step 1: Run all tests**

```
./mvnw test
```

Expected: `BUILD SUCCESS`. All existing tests continue to pass.

- [ ] **Step 2: Final commit if any cleanup is needed**

If any small adjustments were needed to pass all tests, commit them:

```
git add -p
git commit -m "fix(webhook): address any test/compile issues from full suite run"
```

---

## Self-Review Checklist

**Spec coverage:**
- ✅ Inbound: HMAC verify + idempotency + state reconcile → Tasks 2, 4, 5, 6, 7
- ✅ Unknown event type → 200, audit row → Task 5 (`PspWebhookService`)
- ✅ State conflict → log + 200 → Task 5
- ✅ Transaction not found → 400 → Task 5
- ✅ Outbound: entities + migrations → Tasks 8, 9
- ✅ Dispatch + retry backoff → Task 11
- ✅ Dead-letter after 5 attempts → Task 11
- ✅ Scheduler polls every 10s in batches of 50 → Task 12
- ✅ Kafka listener fans out to endpoints → Task 13
- ✅ Merchant endpoint registration API (HTTPS-only, secret shown once) → Task 14
- ✅ WireMock integration tests → Tasks 7, 15
- ✅ HTTPS-only validation on `url` → Task 14 (`RegisterWebhookEndpointRequest`)
- ✅ Secret encrypted at rest (`EncryptedStringConverter`) → Task 9

**Type consistency:**
- `WebhookDeliveryStatus` enum used consistently as `.name()` string in entity column
- `HmacSigningService.sign()` and `.verify()` match usage in `PspWebhookService` and `WebhookDispatchService`
- `retryDelaySeconds` is `public static` to allow direct test access in `WebhookDispatchServiceTest`
