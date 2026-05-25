package com.kimpay.payment.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CachedBodyHttpServletRequestTest {

    @Test
    void bodyIsReadableMultipleTimes() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/payments");
        req.setContent("{\"amount\":10}".getBytes(StandardCharsets.UTF_8));

        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(req);

        String first = new String(cached.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String second = new String(cached.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(first).isEqualTo("{\"amount\":10}");
        assertThat(second).isEqualTo("{\"amount\":10}"); // re-readable: the controller can still read it
        assertThat(cached.getCachedBody()).isEqualTo("{\"amount\":10}".getBytes(StandardCharsets.UTF_8));
    }
}
