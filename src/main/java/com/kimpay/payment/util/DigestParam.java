package com.kimpay.payment.util;

public enum DigestParam {
    PAYMENT_SUMMARY("PaymentSummary"),
    PAYMENT_ID("PaymentId"),
    REQUEST_ID("RequestId"),
    CURRENCY("Currency"),
    AMOUNT("Amount"),
    PAYMENT_RESULT_CODE("PaymentResultCode"),
    PAYMENT_RESULT_MESSAGE("PaymentResultMessage"),
    PAYMENT_STATUS("PaymentStatus"),
    RESULT_CODE("ResultCode"),
    RESULT_MESSAGE("ResultMessage"),
    RESULT_STATUS("ResultStatus"),
    DURATION("DURATION");

    private final String key;

    DigestParam(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
