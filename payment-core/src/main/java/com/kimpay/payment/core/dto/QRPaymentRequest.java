package com.kimpay.payment.core.dto;

public record QRPaymentRequest(
    Long userId,
    String qrData,
    Long walletId,
    Long paymentMethodId,
    String idempotencyKey
) {
}
