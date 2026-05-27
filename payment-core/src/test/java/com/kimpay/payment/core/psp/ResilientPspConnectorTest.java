package com.kimpay.payment.core.psp;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResilientPspConnectorTest {

    private ResilientPspConnector newConnector(PspConnector delegate, int minCalls) {
        CircuitBreakerRegistry cbReg = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(minCalls)
                .minimumNumberOfCalls(minCalls)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build());
        TimeLimiterRegistry tlReg = TimeLimiterRegistry.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(200)).build());
        return new ResilientPspConnector(delegate,
                cbReg.circuitBreaker("psp"),
                tlReg.timeLimiter("psp"),
                Executors.newFixedThreadPool(2),
                30);
    }

    @Test
    void delegatesSuccessfulCalls() {
        PspConnector delegate = mock(PspConnector.class);
        PspResult ok = PspResult.ok(PspStatus.CAPTURED, "ref_1");
        when(delegate.capture("ref_1", BigDecimal.TEN)).thenReturn(ok);

        ResilientPspConnector connector = newConnector(delegate, 5);
        assertThat(connector.capture("ref_1", BigDecimal.TEN)).isEqualTo(ok);
    }

    @Test
    void opensBreakerAfterRepeatedFailuresThenThrowsPspUnavailable() {
        PspConnector delegate = mock(PspConnector.class);
        when(delegate.capture(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("boom"));

        ResilientPspConnector connector = newConnector(delegate, 5);

        for (int i = 0; i < 5; i++) {
            try { connector.capture("ref", BigDecimal.ONE); } catch (RuntimeException ignored) {}
        }
        assertThatThrownBy(() -> connector.capture("ref", BigDecimal.ONE))
                .isInstanceOf(PspUnavailableException.class)
                .extracting("retryAfterSeconds").isEqualTo(30L);
    }
}
