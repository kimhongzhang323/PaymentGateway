package com.kimpay.payment.util;

public enum DigestParam {
    /** Summary or description of the payment transaction */
    PAYMENT_SUMMARY("PaymentSummary"),

    /** Unique identifier for the payment transaction */
    PAYMENT_ID("PaymentId"),

    /** Unique identifier for the payment request */
    REQUEST_ID("RequestId"),

    /** Currency code (e.g., USD, EUR, KRW) */
    CURRENCY("Currency"),

    /** Transaction amount */
    AMOUNT("Amount"),

    /** Result code returned from payment gateway */
    PAYMENT_RESULT_CODE("PaymentResultCode"),

    /** Result message returned from payment gateway */
    PAYMENT_RESULT_MESSAGE("PaymentResultMessage"),

    /** Current status of the payment (e.g., SUCCESS, FAILED, PENDING) */
    PAYMENT_STATUS("PaymentStatus"),

    /** General result code for the operation */
    RESULT_CODE("ResultCode"),

    /** General result message for the operation */
    RESULT_MESSAGE("ResultMessage"),

    /** General status of the operation result */
    RESULT_STATUS("ResultStatus"),

    /** Duration/elapsed time of the operation in milliseconds */
    DURATION("DURATION");

    private final String key;

    DigestParam(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
