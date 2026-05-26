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

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Set;

public class RequestSignatureFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final SignatureVerificationService signatureService;
    private final NonceService nonceService;
    private final long toleranceSeconds;
    private final long maxBodyBytes;

    public RequestSignatureFilter(SignatureVerificationService signatureService,
                                  NonceService nonceService,
                                  long toleranceSeconds,
                                  long maxBodyBytes) {
        this.signatureService = signatureService;
        this.nonceService = nonceService;
        this.toleranceSeconds = toleranceSeconds;
        this.maxBodyBytes = maxBodyBytes;
    }

    /** Sign every non-safe method (POST, PUT, PATCH, DELETE, ...); skip safe read methods. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SAFE_METHODS.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof MerchantPrincipal principal)) {
            reject(response);
            return;
        }

        if (request.getContentLengthLong() > maxBodyBytes) {
            reject(response);
            return;
        }

        String timestamp = request.getHeader("X-Kimpay-Timestamp");
        String nonce = request.getHeader("X-Kimpay-Nonce");
        String signature = request.getHeader("X-Kimpay-Signature");
        if (timestamp == null || nonce == null || signature == null) {
            reject(response);
            return;
        }

        long now = System.currentTimeMillis() / 1000;
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            reject(response);
            return;
        }
        if (Math.abs(now - ts) > toleranceSeconds) {
            reject(response);
            return;
        }

        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);
        byte[] body = cached.getCachedBody();
        if (body.length > maxBodyBytes) {
            reject(response);
            return;
        }
        String bodyHash = Base64.getEncoder().encodeToString(sha256(body));
        String canonical = request.getMethod() + "." + request.getRequestURI() + "."
                + timestamp + "." + nonce + "." + bodyHash;

        if (!signatureService.verifyMerchantSignature(principal.merchantId(), canonical, signature)) {
            reject(response);
            return;
        }
        if (!nonceService.registerNonce(principal.keyId(), nonce)) {
            reject(response);
            return;
        }

        chain.doFilter(cached, response);
    }

    private byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"SEC-001\",\"message\":\"Request signature verification failed\"}");
    }
}
