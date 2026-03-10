package com.kimpay.payment.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kimpay.payment.config.KafkaPaymentProperties;
import com.kimpay.payment.core.event.PaymentEvent;
import com.kimpay.payment.core.event.PaymentEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@Primary
@RequiredArgsConstructor
@Slf4j
public class KafkaPaymentEventPublisher implements PaymentEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final KafkaPaymentProperties kafkaPaymentProperties;

    @Override
    public void publish(PaymentEvent event) {
        if (!kafkaPaymentProperties.isEnabled()) {
            return;
        }

        try {
            String payload = objectMapper.writeValueAsString(event);
            String key = event.transactionId() == null ? "unknown" : String.valueOf(event.transactionId());
            kafkaTemplate.send(kafkaPaymentProperties.getPaymentTopic(), key, payload)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("Failed to publish payment event to Kafka topic {}: {}",
                                    kafkaPaymentProperties.getPaymentTopic(), ex.getMessage());
                        }
                    });
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize payment event for Kafka: {}", ex.getMessage());
        }
    }
}
