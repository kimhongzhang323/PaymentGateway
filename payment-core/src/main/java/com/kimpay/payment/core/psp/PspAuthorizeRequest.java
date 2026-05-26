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
