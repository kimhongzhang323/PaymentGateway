package com.kimpay.payment.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class PaymentLogger {

    private final Logger logger;
    private final String className;

    private PaymentLogger(Logger logger, String className) {
        this.logger = logger;
        this.className = className;
    }

    public static PaymentLogger getLogger(Class<?> clazz) {
        Logger logger = LoggerFactory.getLogger(clazz);
        return new PaymentLogger(logger, clazz.getSimpleName());
    }

    public void info(String message, Object... args) {
        logger.info(formatMessage(message), args);
    }

    public void error(String message, Throwable throwable) {
        logger.error(formatMessage(message), throwable);
    }

    public void error(String message, Object... args) {
        logger.error(formatMessage(message), args);
    }

    public void warn(String message, Object... args) {
        logger.warn(formatMessage(message), args);
    }

    public void debug(String message, Object... args) {
        logger.debug(formatMessage(message), args);
    }

    public void sensitiveInfo(String message) {
        logger.info(formatMessage("[SENSITIVE] " + maskSensitiveData(message)));
    }

    public PaymentLogger withContext(String key, String value) {
        MDC.put(key, value);
        return this;
    }

    public PaymentLogger withTransactionId(String transactionId) {
        MDC.put("transactionId", transactionId);
        return this;
    }

    public PaymentLogger withUserId(String userId) {
        MDC.put("userId", userId);
        return this;
    }

    public void clearContext() {
        MDC.clear();
    }

    private String formatMessage(String message) {
        return String.format("[%s] %s", className, message);
    }

    private String maskSensitiveData(String data) {
        if (data == null || data.length() <= 4) {
            return "****";
        }
        return data.substring(0, 4) + "****" + data.substring(data.length() - 4);
    }
}
