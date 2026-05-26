package com.kimpay.payment.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kimpay.payment.core.event.PaymentEvent;
import com.kimpay.payment.core.repository.WebhookDeliveryRepository;
import com.kimpay.payment.core.repository.WebhookEndpointRepository;
import com.kimpay.payment.domain.entity.WebhookDelivery;
import com.kimpay.payment.domain.entity.WebhookDeliveryStatus;
import com.kimpay.payment.domain.entity.WebhookEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentEventListener {

    private final ObjectMapper objectMapper;
    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryRepository deliveryRepository;

    @KafkaListener(topics = "${payment.kafka.payment-topic}", groupId = "payment-log-group")
    public void consumePaymentEvent(String message) {
        try {
            PaymentEvent event = objectMapper.readValue(message, PaymentEvent.class);
            log.info("Received Payment Event: Type={}, TxID={}, Amount={} {}",
                    event.eventType(), event.transactionId(), event.amount(), event.currency());
            createWebhookDeliveries(event, message);
        } catch (JsonProcessingException e) {
            log.error("Error deserializing payment event from Kafka", e);
        }
    }

    private void createWebhookDeliveries(PaymentEvent event, String rawPayload) {
        List<WebhookEndpoint> endpoints = endpointRepository
                .findByMerchantIdAndEnabledTrue(event.merchantId());
        for (WebhookEndpoint ep : endpoints) {
            WebhookDelivery delivery = new WebhookDelivery();
            delivery.setEndpointId(ep.getId());
            delivery.setEventType(event.eventType());
            delivery.setPayload(rawPayload);
            delivery.setStatus(WebhookDeliveryStatus.PENDING.name());
            delivery.setNextRetryAt(LocalDateTime.now());
            deliveryRepository.save(delivery);
        }
    }
}
