package com.kimpay.payment.core.dto;

import java.math.BigDecimal;

public record RefundPaymentRequest(
        BigDecimal amount,
        String reason
) {
}
