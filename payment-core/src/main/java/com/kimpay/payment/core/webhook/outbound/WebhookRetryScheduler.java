package com.kimpay.payment.core.webhook.outbound;

import com.kimpay.payment.core.repository.WebhookDeliveryRepository;
import com.kimpay.payment.domain.entity.WebhookDelivery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookRetryScheduler {

    private static final int BATCH_SIZE = 50;

    private final WebhookDeliveryRepository deliveryRepo;
    private final WebhookDispatchService dispatchService;

    @Scheduled(fixedDelay = 10_000)
    public void processDueDeliveries() {
        List<WebhookDelivery> due = deliveryRepo.findDueForRetry(LocalDateTime.now(), BATCH_SIZE);
        if (due.isEmpty()) return;

        log.debug("Processing {} due webhook deliveries", due.size());
        for (WebhookDelivery delivery : due) {
            try {
                dispatchService.dispatch(delivery);
            } catch (Exception e) {
                log.error("Unexpected error dispatching webhook delivery {}: {}", delivery.getId(), e.getMessage());
            }
        }
    }
}
