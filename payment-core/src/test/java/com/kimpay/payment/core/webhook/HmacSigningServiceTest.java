package com.kimpay.payment.core.webhook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HmacSigningServiceTest {

    private final HmacSigningService signer = new HmacSigningService();
    private static final String SECRET = "test-secret-key";

    @Test
    void sign_producesConsistentHexSignature() {
        String sig1 = signer.sign("hello", SECRET);
        String sig2 = signer.sign("hello", SECRET);
        assertThat(sig1).isEqualTo(sig2);
        assertThat(sig1).matches("[0-9a-f]{64}");
    }

    @Test
    void verify_acceptsValidSignature() {
        String body = "payload-body";
        long ts = System.currentTimeMillis() / 1000;
        String sig = signer.sign(ts + "." + body, SECRET);
        assertThat(signer.verify(body, ts, sig, SECRET, 300)).isTrue();
    }

    @Test
    void verify_rejectsInvalidSignature() {
        long ts = System.currentTimeMillis() / 1000;
        assertThat(signer.verify("body", ts, "sha256=badhex", SECRET, 300)).isFalse();
    }

    @Test
    void verify_acceptsSignatureWithSha256Prefix() {
        String body = "payload-body";
        long ts = System.currentTimeMillis() / 1000;
        String bareHex = signer.sign(ts + "." + body, SECRET);
        String prefixed = "sha256=" + bareHex;
        assertThat(signer.verify(body, ts, prefixed, SECRET, 300)).isTrue();
    }

    @Test
    void verify_rejectsStaletimestamp() {
        String body = "payload";
        long staleTs = (System.currentTimeMillis() / 1000) - 400;
        String sig = signer.sign(staleTs + "." + body, SECRET);
        assertThat(signer.verify(body, staleTs, sig, SECRET, 300)).isFalse();
    }

    @Test
    void verify_rejectsFutureTimestamp() {
        String body = "payload";
        long futureTs = (System.currentTimeMillis() / 1000) + 400;
        String sig = signer.sign(futureTs + "." + body, SECRET);
        assertThat(signer.verify(body, futureTs, sig, SECRET, 300)).isFalse();
    }
}
