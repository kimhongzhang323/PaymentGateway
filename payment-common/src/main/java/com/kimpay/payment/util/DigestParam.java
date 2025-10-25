package com.kimpay.payment.util;

public enum DigestParam {
    /** Summary of the payment transaction */
    PAYMENT_SUMMARY("PaymentSummary"),
    /** Unique identifier for the payment */
    PAYMENT_ID("PaymentId"),
    /** Unique identifier for the request */
    REQUEST_ID("RequestId"),
    /** Currency code of the transaction */
    CURRENCY("Currency"),
    /** Transaction amount */
    AMOUNT("Amount"),
    /** Result code from payment processing */
    PAYMENT_RESULT_CODE("PaymentResultCode"),
    /** Result message from payment processing */
    PAYMENT_RESULT_MESSAGE("PaymentResultMessage"),
    /** Current status of the payment */
    PAYMENT_STATUS("PaymentStatus"),
    /** General result code */
    RESULT_CODE("ResultCode"),
    /** General result message */
    RESULT_MESSAGE("ResultMessage"),
    /** General result status */
    RESULT_STATUS("ResultStatus"),
    /** Time duration of the operation */
    DURATION("DURATION");

    private final String key;

    DigestParam(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

