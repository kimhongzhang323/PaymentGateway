package com.kimpay.payment.core.event;

public interface PaymentEventPublisher {
    void publish(PaymentEvent event);
}
