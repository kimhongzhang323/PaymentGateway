package com.kimpay.payment.core.psp;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the default PSP <em>delegate</em> when no other delegate (e.g. a Stripe adapter)
 * is present. The delegate is wrapped by {@code ResilientPspConnector} (the primary
 * {@link PspConnector}); a real adapter replaces the mock by registering a bean named
 * {@code "pspDelegate"}.
 */
@Configuration
public class PspConnectorConfig {

    @Bean("pspDelegate")
    @Qualifier("pspDelegate")
    @ConditionalOnMissingBean(name = "pspDelegate")
    public PspConnector mockAcquirerConnector() {
        return new MockAcquirerConnector();
    }
}
