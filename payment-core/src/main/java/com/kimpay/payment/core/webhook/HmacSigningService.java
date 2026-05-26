package com.kimpay.payment.core.webhook;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Service
public class HmacSigningService {

    private static final String ALGORITHM = "HmacSHA256";

    /** Signs {@code message} with {@code secret} and returns a lowercase hex string (no prefix). */
    public String sign(String message, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }

    /**
     * Verifies an inbound signature.
     * Expected signature format: bare 64-char lowercase hex, or the same hex with a "sha256=" prefix.
     * Both forms are accepted for interoperability with webhook senders that include the prefix.
     * Signed message: {@code timestamp + "." + body}.
     */
    public boolean verify(String body, long timestampSeconds, String signature, String secret, long toleranceSeconds) {
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - timestampSeconds) > toleranceSeconds) {
            return false;
        }
        String expected = sign(timestampSeconds + "." + body, secret);
        // Strip optional "sha256=" prefix for flexibility
        String candidate = signature.startsWith("sha256=") ? signature.substring(7) : signature;
        return constantTimeEquals(expected, candidate);
    }

    private boolean constantTimeEquals(String a, String b) {
        // Early-exit on length mismatch is safe: both `expected` (from sign()) and `candidate`
        // (after stripping any "sha256=" prefix) must be 64-char hex strings when the signature
        // format is correct. A length difference only occurs when the candidate is malformed, so
        // no timing information about the secret is leaked.
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
