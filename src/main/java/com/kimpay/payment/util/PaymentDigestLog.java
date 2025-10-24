package com.kimpay.payment.util;
/**
 * =============================================================================
 * PaymentDigestLog.java
 * -----------------------------------------------------------------------------
 * Project      : payment
 * Module       : com.kimpay.payment.util
 * Author       : kimho
 * Created On   : 25/10/2025
 * Last Modified: 1:23 am
 * -----------------------------------------------------------------------------
 * Description  : PaymentDigestLog - Core component or utility class.
 * <p>
 * -----------------------------------------------------------------------------
 * COPYRIGHT NOTICE
 * -----------------------------------------------------------------------------
 * © 2025 Kimpay Technologies. All Rights Reserved.
 * Unauthorized copying, modification, distribution, or disclosure of this
 * file, via any medium, is strictly prohibited. This file contains proprietary
 * and confidential information of Kimpay Technologies.
 * -----------------------------------------------------------------------------
 * SECURITY NOTICE
 * -----------------------------------------------------------------------------
 * This class may process sensitive financial or personal data. Ensure all
 * logs, outputs, and interactions comply with internal data-handling policies
 * and regulatory requirements (e.g., PCI DSS, GDPR).
 * =============================================================================
 */

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class PaymentDigestLog {

    private static final Logger digestLog = LoggerFactory.getLogger("com.kimpay.payment.digest");


    public static void logPaymentDigest(Map<DigestParam, Object> data) {
        if (data == null || data.isEmpty()) {
            digestLog.warn("[PaymentDigestLog] Empty data map provided.");
            return;
        }

        String message = data.entrySet().stream()
                .map(entry -> String.format("[%s,%s]", entry.getKey().key(), safe(entry.getValue())))
                .collect(Collectors.joining(", "));

        digestLog.info(message);
    }

    private static String safe(Object value) {
        if (value == null) return "N/A";
        String str = value.toString().trim();
        return str.isEmpty() ? "N/A" : str;
    }

    public static class Builder {
        private final Map<DigestParam, Object> map = new LinkedHashMap<>();

        public Builder put(DigestParam key, Object value) {
            map.put(key, value);
            return this;
        }

        public void log() {
            PaymentDigestLog.logPaymentDigest(map);
        }
    }
}

