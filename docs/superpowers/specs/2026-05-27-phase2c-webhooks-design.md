# Phase 2c — Inbound PSP Webhooks & Outbound Merchant Webhooks

**Date:** 2026-05-27
**Status:** Approved
**Approach:** Option A — sequential: Inbound PSP webhooks → Outbound merchant webhooks
**No external PSP SDK** — `MockAcquirerConnector` remains the PSP; no Stripe dependency.

---

## 1. Goal

Complete Phase 2 payment-correctness by closing two async loops:

1. **Inbound** — accept signed PSP webhook events, reconcile `Transaction` state asynchronously.
2. **Outbound** — deliver signed webhook notifications to merchant-registered endpoints with retry and dead-letter handling.

---

## 2. Architecture & Module Placement

```
Slice 1: Inbound PSP Webhooks
  payment-api/controller/PspWebhookController.java
  payment-core/service/PspWebhookService.java
  payment-api/src/main/resources/db/migration/V6__psp_webhook_events.sql

Slice 2: Outbound Merchant Webhooks
  payment-domain/entity/WebhookEndpoint.java
  payment-domain/entity/WebhookDelivery.java
  payment-core/repository/WebhookEndpointRepository.java
  payment-core/repository/WebhookDeliveryRepository.java
  payment-core/service/WebhookDispatchService.java
  payment-core/service/WebhookRetryScheduler.java
  payment-core/listener/PaymentEventListener.java
  payment-api/src/main/resources/db/migration/V7__webhook_tables.sql
```

Module boundaries:
- Entities → `payment-domain`
- Repositories, services, listeners → `payment-core`
- Controllers, config → `payment-api`

---

## 3. Data Model

### V6 — psp_webhook_events (inbound idempotency)

```sql
CREATE TABLE psp_webhook_events (
    id            BIGSERIAL PRIMARY KEY,
    event_id      VARCHAR(100) NOT NULL UNIQUE,
    event_type    VARCHAR(100) NOT NULL,
    payload       TEXT NOT NULL,
    processed_at  TIMESTAMP NOT NULL
);
```

`event_id` unique constraint is the idempotency guard — duplicate deliveries from the PSP are no-ops.

### V7 — webhook_endpoints + webhook_deliveries (outbound)

```sql
CREATE TABLE webhook_endpoints (
    id          BIGSERIAL PRIMARY KEY,
    merchant_id BIGINT NOT NULL REFERENCES merchants(id),
    url         VARCHAR(2048) NOT NULL,
    secret      VARCHAR(500) NOT NULL,  -- AES-256-GCM encrypted, sized for ciphertext
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP NOT NULL
);

CREATE INDEX idx_webhook_endpoints_merchant ON webhook_endpoints(merchant_id);

CREATE TABLE webhook_deliveries (
    id              BIGSERIAL PRIMARY KEY,
    endpoint_id     BIGINT NOT NULL REFERENCES webhook_endpoints(id),
    event_type      VARCHAR(100) NOT NULL,
    payload         TEXT NOT NULL,
    status          VARCHAR(20) NOT NULL,  -- PENDING, SUCCESS, FAILED, DEAD
    attempts        INT NOT NULL DEFAULT 0,
    next_retry_at   TIMESTAMP,
    last_response   VARCHAR(1000),
    created_at      TIMESTAMP NOT NULL
);

CREATE INDEX idx_webhook_deliveries_pending ON webhook_deliveries(status, next_retry_at)
    WHERE status IN ('PENDING', 'FAILED');
```

### Retry schedule (exponential backoff)

| Attempt | Delay |
|---------|-------|
| 1 | 30 seconds |
| 2 | 5 minutes |
| 3 | 30 minutes |
| 4 | 2 hours |
| 5 | 8 hours |
| > 5 | → DEAD |

---

## 4. Signing Scheme

Both directions use **HMAC-SHA256**. Headers on every request/event:

```
X-Kimpay-Signature: sha256=<hex-encoded-hmac>
X-Kimpay-Timestamp: <unix-epoch-seconds>
```

Receiver verifies:
1. Timestamp within ±300s of now (replay protection).
2. HMAC-SHA256 over `timestamp + "." + raw-body` matches the header value.

**Inbound secret:** `PSP_WEBHOOK_SECRET` environment variable (single shared secret).
**Outbound secret:** per `WebhookEndpoint.secret`, stored encrypted with `EncryptedStringConverter`.

---

## 5. Data Flow

### Slice 1 — Inbound PSP Webhook

```
MockAcquirerConnector / test client
  → POST /api/webhooks/psp
      Headers: X-Kimpay-Signature, X-Kimpay-Timestamp
      Body: { event_id, event_type, transaction_id, status, ... }

PspWebhookController
  → delegates to PspWebhookService

PspWebhookService
  1. Verify timestamp window (±300s) → 400 if stale
  2. Verify HMAC-SHA256 using PSP_WEBHOOK_SECRET → 400 if invalid
  3. Check psp_webhook_events for duplicate event_id → 200 if exists (already processed)
  4. Load Transaction by id; apply state transition via entity method
  5. Save Transaction
  6. Persist psp_webhook_events row
  7. Publish Kafka PaymentEvent → triggers outbound webhook path
  → 200 OK
```

**Unknown event_type:** log at WARN level, persist event row (for audit), return 200. Do not retry unknown types.

**State conflict (e.g. already CAPTURED):** log at WARN, return 200. Idempotent — do not re-apply.

### Slice 2 — Outbound Merchant Webhook

```
Kafka (PaymentEvent topic)
  → PaymentEventListener (@KafkaListener, payment-core)
  → For each enabled WebhookEndpoint of the merchant:
      INSERT webhook_deliveries (status=PENDING, next_retry_at=now(), attempts=0)

WebhookRetryScheduler (@Scheduled every 10s)
  → SELECT * FROM webhook_deliveries
      WHERE status IN ('PENDING','FAILED') AND next_retry_at <= now()
      LIMIT 50  (bounded batch to avoid memory pressure)
  → for each delivery:
      WebhookDispatchService:
        1. Decrypt endpoint secret
        2. Build HMAC-SHA256 signature over (timestamp + "." + payload)
        3. POST to merchant URL (RestClient, 5s connect / 10s read timeout)
        4. 2xx response → UPDATE status=SUCCESS
        5. non-2xx / timeout → attempts++
            if attempts < 5 → status=FAILED, compute next_retry_at
            if attempts >= 5 → status=DEAD
```

---

## 6. API

### Inbound

```
POST /api/webhooks/psp
  Public endpoint (no merchant auth — authenticated by HMAC signature)
  Headers: X-Kimpay-Signature, X-Kimpay-Timestamp
  Body: PspWebhookEvent { event_id, event_type, transaction_id, status, amount?, currency? }
  Response: 200 OK (always, once signature verified — PSP should not retry on business errors)
            400 Bad Request (invalid signature or stale timestamp only)
```

### Outbound management (merchant-facing, Phase 2c scope)

```
POST   /api/webhook-endpoints          — register a new endpoint URL
GET    /api/webhook-endpoints          — list merchant's endpoints
DELETE /api/webhook-endpoints/{id}     — remove an endpoint
```

Webhook secret is generated server-side (UUID-v4 base), shown once at registration, never retrievable.

---

## 7. Error Handling

| Scenario | Behaviour |
|---|---|
| Invalid inbound signature | 400, log WARN |
| Stale inbound timestamp | 400, log WARN |
| Duplicate inbound event_id | 200, no-op |
| Unknown inbound event_type | 200, audit row persisted |
| Transaction not found for inbound event | 400, log ERROR |
| Illegal state transition | 200, log WARN (idempotent) |
| Outbound HTTP 2xx | SUCCESS |
| Outbound HTTP non-2xx | FAILED, retry scheduled |
| Outbound timeout | FAILED, retry scheduled |
| Outbound max attempts reached | DEAD — manual replay required |
| Scheduler exception on one delivery | Catch + log, continue batch |

---

## 8. Testing

### Unit tests (no Spring context, Mockito)

- `PspWebhookService`: valid signature accepted, invalid signature rejected (400), stale timestamp rejected (400), duplicate event_id → no-op, each supported event_type → correct Transaction state transition, unknown event_type → audit row + 200.
- `WebhookDispatchService`: HMAC signature computed correctly, 2xx → SUCCESS, 4xx → FAILED + attempt count incremented, timeout → FAILED, attempt 5 → DEAD.
- Retry backoff math: attempt 1→30s, 2→5m, 3→30m, 4→2h, 5→8h.

### Integration tests (`@SpringBootTest` + H2 + WireMock)

- POST `/api/webhooks/psp` with valid HMAC → Transaction status updated in DB, `psp_webhook_events` row persisted.
- POST `/api/webhooks/psp` duplicate `event_id` → 200, no second state transition.
- Kafka event → `webhook_deliveries` row created → scheduler fires → WireMock records HTTP call to merchant URL.
- WireMock returns 500 → delivery status=FAILED, attempts=1, next_retry_at set.
- After max attempts → status=DEAD.

### E2E

- Simulate full async loop: POST `/api/webhooks/psp` (PSP event) → Transaction reconciled → Kafka event published → outbound delivery dispatched to WireMock merchant endpoint → delivery status=SUCCESS.

---

## 9. Security Checklist

- Inbound endpoint is public but gated solely by HMAC + timestamp — no merchant auth cookie/token accepted.
- Outbound secret stored encrypted (`EncryptedStringConverter`, AES-256-GCM); never logged.
- Secret shown once at endpoint registration; not stored in plaintext; not returned in subsequent GET calls.
- Webhook payload contains no PANs, CVV, or raw secrets.
- `RestClient` follows redirects disabled — prevent SSRF via redirect chain.
- Merchant URL validated: must be `https://` (enforce in `WebhookEndpoint` entity validation).

---

## 10. Exit Criteria

- All unit and integration tests green.
- POST `/api/webhooks/psp` with valid signature reconciles Transaction state.
- Duplicate inbound event is a 200 no-op (idempotent).
- Kafka payment event → outbound delivery created → HTTP POST fired to merchant endpoint.
- Failed delivery retried with exponential backoff; dead after 5 attempts.
- Merchant endpoint registration API returns signing secret once, never again.
