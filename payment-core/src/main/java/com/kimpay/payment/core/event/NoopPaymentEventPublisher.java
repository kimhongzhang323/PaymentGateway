package com.kimpay.payment.core.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(PaymentEventPublisher.class)
public class NoopPaymentEventPublisher implements PaymentEventPublisher {
    @Override
    public void publish(PaymentEvent event) {
        // Intentionally no-op. This keeps payment flow independent from transport.
    }
}
