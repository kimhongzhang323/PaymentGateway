package com.kimpay.payment.security;

import com.kimpay.payment.core.security.NonceService;
import com.kimpay.payment.core.service.SignatureVerificationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class RequestSignatureFilter extends OncePerRequestFilter {

    private final SignatureVerificationService signatureService;
    private final NonceService nonceService;
    private final long toleranceSeconds;

    public RequestSignatureFilter(SignatureVerificationService signatureService,
                                  NonceService nonceService,
                                  long toleranceSeconds) {
        this.signatureService = signatureService;
        this.nonceService = nonceService;
        this.toleranceSeconds = toleranceSeconds;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        return !("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof MerchantPrincipal principal)) {
            reject(response, "Authentication required");
            return;
        }

        String timestamp = request.getHeader("X-Kimpay-Timestamp");
        String nonce = request.getHeader("X-Kimpay-Nonce");
        String signature = request.getHeader("X-Kimpay-Signature");
        if (timestamp == null || nonce == null || signature == null) {
            reject(response, "Missing signature headers");
            return;
        }

        long now = System.currentTimeMillis() / 1000;
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            reject(response, "Invalid timestamp");
            return;
        }
        if (Math.abs(now - ts) > toleranceSeconds) {
            reject(response, "Stale timestamp");
            return;
        }

        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(request);
        byte[] body = wrapped.getInputStream().readAllBytes();
        String bodyString = new String(body, StandardCharsets.UTF_8);
        String canonical = timestamp + "." + nonce + "." + bodyString;

        if (!signatureService.verifyMerchantSignature(principal.merchantId(), canonical, signature)) {
            reject(response, "Invalid signature");
            return;
        }
        if (!nonceService.registerNonce(principal.keyId(), nonce)) {
            reject(response, "Replay detected");
            return;
        }

        chain.doFilter(wrapped, response);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"code\":\"SEC-001\",\"message\":\"Request signature verification failed\"}");
    }
}
