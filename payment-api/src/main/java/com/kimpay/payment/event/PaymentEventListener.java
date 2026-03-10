package com.kimpay.payment.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kimpay.payment.core.event.PaymentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentEventListener {

    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "${payment.kafka.payment-topic}", groupId = "payment-log-group")
    public void consumePaymentEvent(String message) {
        try {
            PaymentEvent event = objectMapper.readValue(message, PaymentEvent.class);
            log.info("Received Payment Event: Type={}, TxID={}, Amount={} {}", 
                    event.eventType(), event.transactionId(), event.amount(), event.currency());
            
            // Here you could trigger further processing, like sending notification emails, 
            // updating external ledgers, or starting shipping workflows.
            
        } catch (JsonProcessingException e) {
            log.error("Error deserializing payment event from Kafka", e);
        }
    }
}
