package com.kimpay.payment.core.webhook.outbound;

import com.kimpay.payment.core.repository.WebhookDeliveryRepository;
import com.kimpay.payment.core.repository.WebhookEndpointRepository;
import com.kimpay.payment.core.webhook.HmacSigningService;
import com.kimpay.payment.domain.entity.WebhookDelivery;
import com.kimpay.payment.domain.entity.WebhookDeliveryStatus;
import com.kimpay.payment.domain.entity.WebhookEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookDispatchService {

    private static final int MAX_ATTEMPTS = 5;

    private final WebhookDeliveryRepository deliveryRepo;
    private final WebhookEndpointRepository endpointRepo;
    private final HmacSigningService hmac;
    private final RestClient restClient;

    public void dispatch(WebhookDelivery delivery) {
        WebhookEndpoint endpoint = endpointRepo.findById(delivery.getEndpointId()).orElse(null);
        if (endpoint == null) {
            log.error("Endpoint {} not found for delivery {} — marking DEAD", delivery.getEndpointId(), delivery.getId());
            delivery.setStatus(WebhookDeliveryStatus.DEAD.name());
            delivery.setLastResponse("Endpoint not found");
            deliveryRepo.save(delivery);
            return;
        }

        long ts = Instant.now().getEpochSecond();
        String signature = "sha256=" + hmac.sign(ts + "." + delivery.getPayload(), endpoint.getSecret());

        try {
            restClient.post()
                    .uri(endpoint.getUrl())
                    .header("X-Kimpay-Signature", signature)
                    .header("X-Kimpay-Timestamp", String.valueOf(ts))
                    .header("X-Kimpay-Event", delivery.getEventType())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(delivery.getPayload())
                    .retrieve()
                    .toBodilessEntity();

            delivery.setAttempts(delivery.getAttempts() + 1);
            delivery.setStatus(WebhookDeliveryStatus.SUCCESS.name());
            delivery.setLastResponse("200 OK");
            log.info("Webhook delivered: delivery={} endpoint={}", delivery.getId(), endpoint.getUrl());

        } catch (Exception e) {
            int newAttempts = delivery.getAttempts() + 1;
            delivery.setAttempts(newAttempts);
            String msg = e.getMessage();
            delivery.setLastResponse(msg != null ? msg.substring(0, Math.min(msg.length(), 1000)) : "error");

            if (newAttempts >= MAX_ATTEMPTS) {
                delivery.setStatus(WebhookDeliveryStatus.DEAD.name());
                log.warn("Webhook delivery dead after {} attempts: delivery={}", newAttempts, delivery.getId());
            } else {
                delivery.setStatus(WebhookDeliveryStatus.FAILED.name());
                delivery.setNextRetryAt(LocalDateTime.now().plusSeconds(retryDelaySeconds(newAttempts)));
                log.warn("Webhook delivery failed (attempt {}), will retry: delivery={}", newAttempts, delivery.getId());
            }
        }

        deliveryRepo.save(delivery);
    }

    /** Exponential backoff: 30s → 5m → 30m → 2h → 8h */
    public static long retryDelaySeconds(int attemptNumber) {
        return switch (attemptNumber) {
            case 1 -> 30;
            case 2 -> 300;
            case 3 -> 1800;
            case 4 -> 7200;
            default -> 28800;
        };
    }
}
