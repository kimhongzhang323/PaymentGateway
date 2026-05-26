package com.kimpay.payment.core.webhook.inbound;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public record PspWebhookPayload(
        @JsonProperty("event_id")       String eventId,
        @JsonProperty("event_type")     String eventType,
        @JsonProperty("transaction_id") Long transactionId,
        @JsonProperty("status")         String status,
        @JsonProperty("amount")         BigDecimal amount,
        @JsonProperty("currency")       String currency
) {}
