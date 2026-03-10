package com.kimpay.payment.core.dto;

import com.kimpay.payment.domain.entity.Transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentResponse(
        Long id,
        Long userId,
        Long merchantId,
        Long paymentMethodId,
        BigDecimal amount,
        String currency,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static PaymentResponse from(Transaction transaction) {
        return new PaymentResponse(
                transaction.getId(),
                transaction.getUserId(),
                transaction.getMerchantId(),
                transaction.getPaymentMethodId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus(),
                transaction.getCreatedAt(),
                transaction.getUpdatedAt()
        );
    }
}
