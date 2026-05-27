package com.kimpay.payment.config;

import com.kimpay.payment.security.RateLimitProperties;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.redisson.cas.RedissonBasedProxyManager;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.command.CommandAsyncExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Builds a Bucket4j distributed {@link ProxyManager} backed by the existing
 * Redisson client, so per-API-key token buckets are shared across all gateway
 * nodes. No second Redis/Redisson connection is created — the singleton
 * {@link RedissonClient} bean is reused via its {@link CommandAsyncExecutor}.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    /** Drop idle buckets ~10 minutes after they would refill back to capacity. */
    private static final Duration BUCKET_IDLE_TTL = Duration.ofMinutes(10);

    @Bean
    public ProxyManager<String> rateLimitProxyManager(RedissonClient redissonClient) {
        CommandAsyncExecutor commandExecutor = ((Redisson) redissonClient).getCommandExecutor();
        return RedissonBasedProxyManager.builderFor(commandExecutor)
                .withExpirationStrategy(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(BUCKET_IDLE_TTL))
                .build();
    }
}
