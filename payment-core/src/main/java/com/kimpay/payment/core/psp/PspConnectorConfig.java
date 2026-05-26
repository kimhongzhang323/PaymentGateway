package com.kimpay.payment.core.psp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the default {@link PspConnector} when no other implementation (e.g. a Stripe adapter
 * in payment-api) is present. Declared as a {@code @Bean} with {@code @ConditionalOnMissingBean}
 * per architecture-principles.md — never a scanned conditional {@code @Component}.
 */
@Configuration
public class PspConnectorConfig {

    @Bean
    @ConditionalOnMissingBean(PspConnector.class)
    public PspConnector mockAcquirerConnector() {
        return new MockAcquirerConnector();
    }
}
