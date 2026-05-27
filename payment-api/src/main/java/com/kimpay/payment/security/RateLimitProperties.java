package com.kimpay.payment.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-API-key rate-limit tunables. Bound from {@code payment.ratelimit.*}.
 * A token bucket of {@code capacity} tokens refills {@code refillTokens} every
 * {@code refillPeriod}. Per-key overrides may set a different capacity.
 */
@ConfigurationProperties("payment.ratelimit")
public class RateLimitProperties {

    private boolean enabled = true;
    private long capacity = 100;
    private long refillTokens = 50;
    private Duration refillPeriod = Duration.ofSeconds(1);
    /** keyId -> override capacity (refill rate kept proportional via refillTokens). */
    private Map<String, Long> overrides = new HashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getCapacity() { return capacity; }
    public void setCapacity(long capacity) { this.capacity = capacity; }
    public long getRefillTokens() { return refillTokens; }
    public void setRefillTokens(long refillTokens) { this.refillTokens = refillTokens; }
    public Duration getRefillPeriod() { return refillPeriod; }
    public void setRefillPeriod(Duration refillPeriod) { this.refillPeriod = refillPeriod; }
    public Map<String, Long> getOverrides() { return overrides; }
    public void setOverrides(Map<String, Long> overrides) { this.overrides = overrides; }

    /** Effective capacity for a key (override if present, else default). */
    public long capacityFor(String keyId) {
        return overrides.getOrDefault(keyId, capacity);
    }
}
