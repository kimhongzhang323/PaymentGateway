package com.kimpay.payment.core.psp;

import java.math.BigDecimal;

/**
 * Transport-agnostic seam to an external acquirer / PSP. Implementations live closer to
 * transport (e.g. a Stripe adapter in payment-api); the default offline fallback is
 * {@code MockAcquirerConnector}. Implementations MUST NOT log PANs, CVV, or secrets.
 */
public interface PspConnector {

    /** Authorize (and optionally capture) a card payment. */
    PspResult authorize(PspAuthorizeRequest request);

    /** Capture a previously-authorized payment by its PSP reference. */
    PspResult capture(String pspReference, BigDecimal amount);

    /** Void (cancel) a previously-authorized, not-yet-captured payment. */
    PspResult voidAuthorization(String pspReference);

    /** Refund a captured payment, in full or part. */
    PspResult refund(String pspReference, BigDecimal amount);
}
