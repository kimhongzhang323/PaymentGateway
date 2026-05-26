package com.kimpay.payment.core.webhook.inbound;

import com.kimpay.payment.core.event.PaymentEvent;
import com.kimpay.payment.core.event.PaymentEventPublisher;
import com.kimpay.payment.core.repository.PspWebhookEventRepository;
import com.kimpay.payment.core.repository.TransactionRepository;
import com.kimpay.payment.core.webhook.HmacSigningService;
import com.kimpay.payment.domain.entity.PspWebhookEvent;
import com.kimpay.payment.domain.entity.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.function.Consumer;

@Slf4j
@Service
public class PspWebhookService {

    private final PspWebhookEventRepository eventRepo;
    private final TransactionRepository transactionRepo;
    private final PaymentEventPublisher publisher;
    private final HmacSigningService hmac;
    private final String webhookSecret;
    private final long toleranceSeconds;

    public PspWebhookService(
            PspWebhookEventRepository eventRepo,
            TransactionRepository transactionRepo,
            PaymentEventPublisher publisher,
            HmacSigningService hmac,
            @Value("${payment.webhook.psp-secret:}") String webhookSecret,
            @Value("${payment.security.timestamp-tolerance-seconds:300}") long toleranceSeconds
    ) {
        this.eventRepo = eventRepo;
        this.transactionRepo = transactionRepo;
        this.publisher = publisher;
        this.hmac = hmac;
        this.webhookSecret = webhookSecret;
        this.toleranceSeconds = toleranceSeconds;
    }

    @Transactional
    public void process(PspWebhookPayload payload, String rawBody, String signature, long timestampSeconds) {
        if (!hmac.verify(rawBody, timestampSeconds, signature, webhookSecret, toleranceSeconds)) {
            throw new IllegalArgumentException("Invalid or stale webhook signature");
        }

        if (eventRepo.existsByEventId(payload.eventId())) {
            log.debug("Duplicate PSP webhook event ignored: {}", payload.eventId());
            return;
        }

        if ("PAYMENT_CAPTURED".equals(payload.eventType())) {
            reconcile(payload, rawBody, Transaction::capture);
        } else if ("PAYMENT_VOIDED".equals(payload.eventType())) {
            reconcile(payload, rawBody, Transaction::voidTransaction);
        } else if ("PAYMENT_REFUNDED".equals(payload.eventType())) {
            reconcile(payload, rawBody, Transaction::refund);
        } else {
            log.warn("Unknown PSP webhook event type: {}", payload.eventType());
            persistAuditRow(payload, rawBody);
        }
    }

    private void reconcile(PspWebhookPayload payload, String rawBody, Consumer<Transaction> transition) {
        Transaction tx = transactionRepo.findById(payload.transactionId())
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + payload.transactionId()));

        try {
            transition.accept(tx);
        } catch (IllegalStateException e) {
            log.warn("PSP webhook state conflict for txn {}: {}", payload.transactionId(), e.getMessage());
            persistAuditRow(payload, rawBody);
            return;
        }

        transactionRepo.save(tx);

        publisher.publish(new PaymentEvent(
                payload.eventType(),
                tx.getId(),
                tx.getUserId(),
                tx.getMerchantId(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getStatus(),
                "Reconciled via PSP webhook",
                LocalDateTime.now()
        ));

        persistAuditRow(payload, rawBody);
    }

    private void persistAuditRow(PspWebhookPayload payload, String rawBody) {
        PspWebhookEvent event = new PspWebhookEvent();
        event.setEventId(payload.eventId());
        event.setEventType(payload.eventType());
        event.setPayload(rawBody);
        event.setProcessedAt(LocalDateTime.now());
        eventRepo.save(event);
    }
}
