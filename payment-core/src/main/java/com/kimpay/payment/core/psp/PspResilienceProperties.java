package com.kimpay.payment.core.psp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for the PSP circuit breaker + timeout. Bound from {@code payment.psp.resilience.*}.
 */
@ConfigurationProperties("payment.psp.resilience")
public class PspResilienceProperties {

    /** Per-call timeout for any PSP operation. */
    private Duration timeout = Duration.ofSeconds(3);
    /** Failure-rate percentage (0-100) that trips the breaker. */
    private float failureRateThreshold = 50f;
    /** Sliding window size (number of calls) used to compute the failure rate. */
    private int slidingWindowSize = 20;
    /** Minimum calls before the rate is evaluated. */
    private int minimumNumberOfCalls = 10;
    /** How long the breaker stays open before half-opening. Also the Retry-After hint. */
    private Duration waitDurationInOpenState = Duration.ofSeconds(30);
    /** Trial calls permitted in half-open state. */
    private int permittedCallsInHalfOpenState = 3;

    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public float getFailureRateThreshold() { return failureRateThreshold; }
    public void setFailureRateThreshold(float v) { this.failureRateThreshold = v; }
    public int getSlidingWindowSize() { return slidingWindowSize; }
    public void setSlidingWindowSize(int v) { this.slidingWindowSize = v; }
    public int getMinimumNumberOfCalls() { return minimumNumberOfCalls; }
    public void setMinimumNumberOfCalls(int v) { this.minimumNumberOfCalls = v; }
    public Duration getWaitDurationInOpenState() { return waitDurationInOpenState; }
    public void setWaitDurationInOpenState(Duration v) { this.waitDurationInOpenState = v; }
    public int getPermittedCallsInHalfOpenState() { return permittedCallsInHalfOpenState; }
    public void setPermittedCallsInHalfOpenState(int v) { this.permittedCallsInHalfOpenState = v; }
}
