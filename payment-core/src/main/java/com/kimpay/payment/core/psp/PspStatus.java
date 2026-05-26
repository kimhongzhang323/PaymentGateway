package com.kimpay.payment.core.psp;

/** Normalized PSP outcome, independent of any specific provider's vocabulary. */
public enum PspStatus {
    AUTHORIZED,
    CAPTURED,
    VOIDED,
    REFUNDED,
    DECLINED,
    ERROR
}
