package com.kimpay.payment.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskerTest {

    @Test
    void masksApiSecretKeys() {
        String in = "auth token sk_test_abcdEFGH1234 used";
        assertThat(SensitiveDataMasker.mask(in)).doesNotContain("abcdEFGH1234");
        assertThat(SensitiveDataMasker.mask(in)).contains("sk_test_***");
    }

    @Test
    void masksLongDigitSequencesLikePan() {
        String in = "card 4111111111111111 charged";
        String out = SensitiveDataMasker.mask(in);
        assertThat(out).doesNotContain("4111111111111111");
        assertThat(out).contains("****");
    }

    @Test
    void leavesPlainTextUntouched() {
        assertThat(SensitiveDataMasker.mask("hello world")).isEqualTo("hello world");
    }

    @Test
    void handlesNull() {
        assertThat(SensitiveDataMasker.mask(null)).isNull();
    }
}
