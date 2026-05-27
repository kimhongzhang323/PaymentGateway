package com.kimpay.payment.security;

import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {

    @SuppressWarnings("unchecked")
    private final ProxyManager<String> proxyManager = mock(ProxyManager.class);
    @SuppressWarnings("unchecked")
    private final RemoteBucketBuilder<String> bucketBuilder = mock(RemoteBucketBuilder.class);
    private final BucketProxy bucket = mock(BucketProxy.class);

    private RateLimitProperties props;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        props = new RateLimitProperties();
        props.setEnabled(true);
        props.setCapacity(100);
        props.setRefillTokens(50);
        filter = new RateLimitFilter(proxyManager, props);
        authenticate("key-123", 1L);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String keyId, Long merchantId) {
        MerchantPrincipal principal = new MerchantPrincipal(merchantId, keyId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @Test
    void deniedRequestReturns429WithRetryAfterAndSec002() throws Exception {
        when(proxyManager.builder()).thenReturn(bucketBuilder);
        when(bucketBuilder.build(eq("payment:ratelimit:key:key-123"), any(Supplier.class)))
                .thenReturn(bucket);
        // rejected(remainingTokens, nanosToWaitForRefill, nanosToWaitForReset)
        ConsumptionProbe rejected = ConsumptionProbe.rejected(0L, 2_000_000_000L, 2_000_000_000L);
        when(bucket.tryConsumeAndReturnRemaining(anyLong())).thenReturn(rejected);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/payments");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("SEC-002");
        assertThat(response.getHeader("Retry-After")).isEqualTo("2");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void redisFailureFailsOpen() throws Exception {
        when(proxyManager.builder()).thenThrow(new RuntimeException("Redis down"));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/payments");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void withinLimitProceeds() throws Exception {
        when(proxyManager.builder()).thenReturn(bucketBuilder);
        when(bucketBuilder.build(eq("payment:ratelimit:key:key-123"), any(Supplier.class)))
                .thenReturn(bucket);
        // consumed(remainingTokens, nanosToWaitForReset)
        ConsumptionProbe consumed = ConsumptionProbe.consumed(99L, 0L);
        when(bucket.tryConsumeAndReturnRemaining(anyLong())).thenReturn(consumed);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/payments");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("99");
    }
}
