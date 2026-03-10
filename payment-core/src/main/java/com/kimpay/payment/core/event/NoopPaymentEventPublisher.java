package com.kimpay.payment.core.event;

import org.springframework.stereotype.Component;

@Component
public class NoopPaymentEventPublisher implements PaymentEventPublisher {
    @Override
    public void publish(PaymentEvent event) {
        // Intentionally no-op. This keeps payment flow independent from transport.
    }
}
