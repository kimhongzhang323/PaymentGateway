package com.kimpay.payment.core.dto;

import java.math.BigDecimal;

public record CreatePaymentRequest(
        Long userId,
        Long merchantId,
        Long paymentMethodId,
        Long walletId,
        BigDecimal amount,
        String currency
) {
}
