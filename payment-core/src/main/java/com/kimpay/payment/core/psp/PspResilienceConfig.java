package com.kimpay.payment.core.psp;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties(PspResilienceProperties.class)
public class PspResilienceConfig {

    @Bean
    public CircuitBreakerRegistry pspCircuitBreakerRegistry(PspResilienceProperties props) {
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(props.getSlidingWindowSize())
                .minimumNumberOfCalls(props.getMinimumNumberOfCalls())
                .failureRateThreshold(props.getFailureRateThreshold())
                .waitDurationInOpenState(props.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(props.getPermittedCallsInHalfOpenState())
                .build());
    }

    @Bean
    public TimeLimiterRegistry pspTimeLimiterRegistry(PspResilienceProperties props) {
        return TimeLimiterRegistry.of(TimeLimiterConfig.custom()
                .timeoutDuration(props.getTimeout())
                .cancelRunningFuture(true)
                .build());
    }

    /** Bounded pool for the TimeLimiter. Spring calls shutdown() on this ExecutorService bean at context close. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService pspExecutor() {
        return Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()));
    }

    @Bean
    @Primary
    public PspConnector resilientPspConnector(@Qualifier("pspDelegate") PspConnector delegate,
                                              CircuitBreakerRegistry cbRegistry,
                                              TimeLimiterRegistry tlRegistry,
                                              ExecutorService pspExecutor,
                                              PspResilienceProperties props) {
        return new ResilientPspConnector(delegate,
                cbRegistry.circuitBreaker("psp"),
                tlRegistry.timeLimiter("psp"),
                pspExecutor,
                props.getWaitDurationInOpenState().toSeconds());
    }
}
