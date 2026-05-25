package com.kimpay.payment.core.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record RefundPaymentRequest(
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        String reason
) {
}
