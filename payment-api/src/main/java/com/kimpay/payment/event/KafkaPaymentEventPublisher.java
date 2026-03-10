package com.kimpay.payment.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kimpay.payment.config.KafkaPaymentProperties;
import com.kimpay.payment.core.event.PaymentEvent;
import com.kimpay.payment.core.event.PaymentEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaPaymentEventPublisher implements PaymentEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaPaymentProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public void publish(PaymentEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            String key = event.transactionId() != null ? event.transactionId().toString() : null;
            
            log.info("Publishing payment event to Kafka. Topic: {}, EventType: {}", 
                    properties.getPaymentTopic(), event.eventType());
            
            kafkaTemplate.send(properties.getPaymentTopic(), key, payload)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.debug("Published event successfully: {}", result.getRecordMetadata());
                    } else {
                        log.error("Failed to publish event to Kafka", ex);
                    }
                });
        } catch (JsonProcessingException e) {
            log.error("Error serializing payment event", e);
        }
    }
}
