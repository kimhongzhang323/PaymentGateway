package com.kimpay.payment.security;

public class KeyManagementException extends RuntimeException {
    public KeyManagementException(String message) {
        super(message);
    }

    public KeyManagementException(String message, Throwable cause) {
        super(message, cause);
    }
}
