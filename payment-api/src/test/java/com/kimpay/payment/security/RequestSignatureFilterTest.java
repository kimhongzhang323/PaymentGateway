package com.kimpay.payment.security;

import com.kimpay.payment.core.security.NonceService;
import com.kimpay.payment.core.service.SignatureVerificationService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RequestSignatureFilterTest {

    private SignatureVerificationService signatureService;
    private NonceService nonceService;
    private RequestSignatureFilter filter;

    @BeforeEach
    void setUp() {
        signatureService = mock(SignatureVerificationService.class);
        nonceService = mock(NonceService.class);
        filter = new RequestSignatureFilter(signatureService, nonceService, 300);
        var auth = new UsernamePasswordAuthenticationToken(
                new MerchantPrincipal(7L, "pk_test_abc"), null,
                List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest signedRequest(String body) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/payments");
        req.setContent(body.getBytes());
        req.addHeader("X-Kimpay-Timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        req.addHeader("X-Kimpay-Nonce", "nonce-1");
        req.addHeader("X-Kimpay-Signature", "c2lnbmF0dXJl");
        return req;
    }

    @Test
    void passesWhenSignatureValidAndNonceFresh() throws Exception {
        when(nonceService.registerNonce(eq("pk_test_abc"), eq("nonce-1"))).thenReturn(true);
        when(signatureService.verifyMerchantSignature(eq(7L), anyString(), eq("c2lnbmF0dXJl"))).thenReturn(true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(signedRequest("{\"amount\":10}"), res, chain);

        verify(chain).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsWhenSignatureInvalid() throws Exception {
        when(nonceService.registerNonce(anyString(), anyString())).thenReturn(true);
        when(signatureService.verifyMerchantSignature(anyLong(), anyString(), anyString())).thenReturn(false);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(signedRequest("{\"amount\":10}"), res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsReplayedNonce() throws Exception {
        when(signatureService.verifyMerchantSignature(anyLong(), anyString(), anyString())).thenReturn(true);
        when(nonceService.registerNonce(anyString(), anyString())).thenReturn(false);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(signedRequest("{\"amount\":10}"), res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsStaleTimestamp() throws Exception {
        when(signatureService.verifyMerchantSignature(anyLong(), anyString(), anyString())).thenReturn(true);
        when(nonceService.registerNonce(anyString(), anyString())).thenReturn(true);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/payments");
        req.setContent("{}".getBytes());
        req.addHeader("X-Kimpay-Timestamp", String.valueOf(System.currentTimeMillis() / 1000 - 10_000));
        req.addHeader("X-Kimpay-Nonce", "nonce-2");
        req.addHeader("X-Kimpay-Signature", "c2ln");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(401);
    }
}
