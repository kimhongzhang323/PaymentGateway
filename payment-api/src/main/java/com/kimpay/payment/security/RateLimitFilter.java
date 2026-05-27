package com.kimpay.payment.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Per-API-key token-bucket rate limiter. Runs after authentication so the
 * {@link MerchantPrincipal#keyId()} is available to key the bucket. Buckets are
 * distributed via a Bucket4j {@link ProxyManager} backed by Redisson, so limits
 * are shared across all gateway nodes.
 *
 * <p>Fails open: any backend (Redis/Redisson) error allows the request through
 * with a WARN log rather than returning a 5xx — availability is preferred over
 * strict enforcement for an optional protective layer.
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String KEY_PREFIX = "payment:ratelimit:key:";
    private static final Set<String> EXEMPT_PATHS =
            Set.of("/actuator/health", "/actuator/info", "/api/webhooks/psp");

    private final ProxyManager<String> proxyManager;
    private final RateLimitProperties props;

    public RateLimitFilter(ProxyManager<String> proxyManager, RateLimitProperties props) {
        this.proxyManager = proxyManager;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !props.isEnabled() || EXEMPT_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof MerchantPrincipal principal)) {
            chain.doFilter(request, response);
            return;
        }

        ConsumptionProbe probe;
        try {
            Bucket bucket = resolveBucket(principal.keyId());
            probe = bucket.tryConsumeAndReturnRemaining(1);
        } catch (RuntimeException e) {
            log.warn("Rate-limit backend unavailable, failing open for key {}", principal.keyId());
            chain.doFilter(request, response);
            return;
        }

        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(request, response);
        } else {
            long retryAfter = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
            reject429(response, retryAfter);
        }
    }

    private Bucket resolveBucket(String keyId) {
        long capacity = props.capacityFor(keyId);
        Supplier<BucketConfiguration> configSupplier = () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.classic(capacity,
                        Refill.greedy(props.getRefillTokens(), props.getRefillPeriod())))
                .build();
        return proxyManager.builder().build(KEY_PREFIX + keyId, configSupplier);
    }

    private void reject429(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"SEC-002\",\"message\":\"Rate limit exceeded\"}");
    }
}
