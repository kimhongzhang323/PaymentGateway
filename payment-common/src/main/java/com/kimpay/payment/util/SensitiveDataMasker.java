package com.kimpay.payment.util;

import java.util.regex.Pattern;

/**
 * =============================================================================
 * SensitiveDataMasker — programmatic API for masking sensitive data in log
 * messages before they are emitted.
 *
 * <p>Covers:
 * <ul>
 *   <li>API secret keys matching the {@code sk_test_} / {@code sk_live_} prefix</li>
 *   <li>PAN-like digit sequences (13–19 contiguous digits)</li>
 * </ul>
 *
 * <p>The logback {@code %replace} directive in {@code logback-spring.xml} acts as
 * a defence-in-depth safety net at the appender level; this class provides the
 * primary programmatic masking API.
 * =============================================================================
 */
public final class SensitiveDataMasker {

    private static final Pattern SECRET_KEY = Pattern.compile("(sk_(?:test|live)_)[A-Za-z0-9_-]+");
    // 13–19 contiguous digits ~ PAN range.
    private static final Pattern PAN = Pattern.compile("\\b\\d{13,19}\\b");

    private SensitiveDataMasker() {}

    public static String mask(String input) {
        if (input == null) {
            return null;
        }
        String out = SECRET_KEY.matcher(input).replaceAll("$1***");
        out = PAN.matcher(out).replaceAll("****");
        return out;
    }
}
