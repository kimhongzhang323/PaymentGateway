package com.kimpay.payment.core.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentEventPublisherConfig {

    @Bean
    @ConditionalOnMissingBean(PaymentEventPublisher.class)
    public PaymentEventPublisher noopPaymentEventPublisher() {
        return new NoopPaymentEventPublisher();
    }
}
