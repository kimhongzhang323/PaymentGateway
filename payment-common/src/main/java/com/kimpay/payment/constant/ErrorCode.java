package com.kimpay.payment.constant;

public enum ErrorCode {

    // ─────── General Errors ───────
    SYSTEM_ERROR("SYS-001", "Unexpected system error"),
    INVALID_REQUEST("REQ-001", "Invalid request data"),
    UNAUTHORIZED("AUTH-001", "Unauthorized access"),
    RESOURCE_NOT_FOUND("RES-404", "Requested resource not found"),

    // ─────── Payment Errors ───────
    PAYMENT_FAILED("PAY-001", "Payment failed during processing"),
    PAYMENT_TIMEOUT("PAY-002", "Payment request timed out"),
    INSUFFICIENT_FUNDS("PAY-003", "Insufficient funds"),
    DUPLICATE_TRANSACTION("PAY-004", "Duplicate transaction detected"),

    // ─────── Merchant Errors ───────
    MERCHANT_NOT_FOUND("MER-001", "Merchant not registered"),
    MERCHANT_INACTIVE("MER-002", "Merchant account inactive"),

    // ─────── Integration Errors ───────
    ADAPTER_ERROR("ADP-001", "Payment adapter failed"),
    CALLBACK_VERIFICATION_FAILED("CB-001", "Callback signature verification failed"),

    // ─────── Validation Errors ───────
    INVALID_AMOUNT("VAL-001", "Invalid payment amount"),
    INVALID_CURRENCY("VAL-002", "Unsupported or invalid currency"),
    INVALID_CARD_DETAILS("VAL-003", "Invalid card details provided"),
    MISSING_REQUIRED_FIELD("VAL-004", "Required field is missing"),

    // ─────── Security Errors ───────
    INVALID_SIGNATURE("SEC-001", "Request signature verification failed"),
    RATE_LIMIT_EXCEEDED("SEC-002", "Rate limit exceeded"),
    IP_BLOCKED("SEC-003", "IP address blocked"),
    SUSPICIOUS_ACTIVITY("SEC-004", "Suspicious activity detected"),

    // ─────── Network/Connectivity Errors ───────
    CONNECTION_FAILED("NET-001", "Failed to connect to payment provider"),
    NETWORK_TIMEOUT("NET-002", "Network request timed out"),
    SERVICE_UNAVAILABLE("NET-003", "Payment service temporarily unavailable"),

    // ─────── Compliance Errors ───────
    SANCTIONED_COUNTRY("CMP-001", "Transaction from sanctioned country"),
    AML_CHECK_FAILED("CMP-002", "Anti-money laundering check failed"),
    REGULATORY_VIOLATION("CMP-003", "Transaction violates regulatory requirements"),

    // ─────── Transaction State Errors ───────
    TRANSACTION_CANCELLED("TXN-001", "Transaction was cancelled"),
    REFUND_FAILED("TXN-002", "Refund processing failed"),
    CHARGEBACK_INITIATED("TXN-003", "Chargeback initiated"),
    TRANSACTION_PENDING("TXN-004", "Transaction is still pending"),

    // ─────── API Errors ───────
    API_KEY_INVALID("API-001", "Invalid API key"),
    QUOTA_EXCEEDED("API-002", "API quota exceeded"),
    ENDPOINT_NOT_FOUND("API-003", "API endpoint not found"),
    METHOD_NOT_ALLOWED("API-004", "HTTP method not allowed");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    @Override
    public String toString() {
        return String.format("[%s] %s", code, message);
    }
}
