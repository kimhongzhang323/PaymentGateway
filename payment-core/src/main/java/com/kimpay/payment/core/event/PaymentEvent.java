package com.kimpay.payment.core.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentEvent(
        String eventType,
        Long transactionId,
        Long userId,
        Long merchantId,
        BigDecimal amount,
        String currency,
        String status,
        String message,
        LocalDateTime occurredAt
) {
}
