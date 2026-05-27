package com.kimpay.payment.core.webhook.inbound;

import com.kimpay.payment.core.event.PaymentEvent;
import com.kimpay.payment.core.event.PaymentEventPublisher;
import com.kimpay.payment.core.repository.PspWebhookEventRepository;
import com.kimpay.payment.core.repository.TransactionRepository;
import com.kimpay.payment.core.webhook.HmacSigningService;
import com.kimpay.payment.domain.entity.PaymentStatus;
import com.kimpay.payment.domain.entity.PspWebhookEvent;
import com.kimpay.payment.domain.entity.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PspWebhookServiceTest {

    @Mock PspWebhookEventRepository eventRepo;
    @Mock TransactionRepository transactionRepo;
    @Mock PaymentEventPublisher publisher;

    HmacSigningService hmac = new HmacSigningService();
    PspWebhookService service;

    private static final String SECRET = "test-webhook-secret";
    private static final long TOLERANCE = 300L;

    @BeforeEach
    void setUp() {
        service = new PspWebhookService(eventRepo, transactionRepo, publisher, hmac, SECRET, TOLERANCE);
    }

    private String sign(long ts, String body) {
        return "sha256=" + hmac.sign(ts + "." + body, SECRET);
    }

    @Test
    void process_validCaptureEvent_updatesTransactionToCaptured() {
        long ts = Instant.now().getEpochSecond();
        PspWebhookPayload payload = new PspWebhookPayload("evt-1", "PAYMENT_CAPTURED", 42L, "CAPTURED", BigDecimal.TEN, "USD");
        String body = """
                {"event_id":"evt-1","event_type":"PAYMENT_CAPTURED","transaction_id":42,"status":"CAPTURED","amount":10,"currency":"USD"}
                """.strip();
        String sig = sign(ts, body);

        Transaction tx = new Transaction();
        tx.setId(42L);
        tx.setMerchantId(1L);
        tx.setUserId(1L);
        tx.setAmount(BigDecimal.TEN);
        tx.setCurrency("USD");
        tx.authorize(); // set to AUTHORIZED first

        when(eventRepo.existsByEventId("evt-1")).thenReturn(false);
        when(transactionRepo.findById(42L)).thenReturn(Optional.of(tx));

        service.process(payload, body, sig, ts);

        assertThat(tx.getStatus()).isEqualTo(PaymentStatus.CAPTURED.name());
        verify(eventRepo).save(any(PspWebhookEvent.class));
        verify(publisher).publish(any(PaymentEvent.class));
    }

    @Test
    void process_duplicateEventId_isNoOp() {
        long ts = Instant.now().getEpochSecond();
        PspWebhookPayload payload = new PspWebhookPayload("evt-dup", "PAYMENT_CAPTURED", 1L, "CAPTURED", BigDecimal.ONE, "USD");
        String body = "{}";
        String sig = sign(ts, body);

        when(eventRepo.existsByEventId("evt-dup")).thenReturn(true);

        service.process(payload, body, sig, ts);

        verify(transactionRepo, never()).findById(any());
        verify(publisher, never()).publish(any());
        verify(eventRepo, never()).save(any(PspWebhookEvent.class));
    }

    @Test
    void process_invalidSignature_throwsIllegalArgumentException() {
        long ts = Instant.now().getEpochSecond();
        PspWebhookPayload payload = new PspWebhookPayload("evt-x", "PAYMENT_CAPTURED", 1L, "CAPTURED", BigDecimal.ONE, "USD");

        assertThatThrownBy(() -> service.process(payload, "{}", "sha256=badhex", ts))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void process_staleTimestamp_throwsIllegalArgumentException() {
        long staleTs = Instant.now().getEpochSecond() - 400;
        PspWebhookPayload payload = new PspWebhookPayload("evt-y", "PAYMENT_CAPTURED", 1L, "CAPTURED", BigDecimal.ONE, "USD");
        String body = "{}";
        String sig = sign(staleTs, body);

        assertThatThrownBy(() -> service.process(payload, body, sig, staleTs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void process_unknownEventType_persistsAuditRowAndReturnsQuietly() {
        long ts = Instant.now().getEpochSecond();
        PspWebhookPayload payload = new PspWebhookPayload("evt-z", "UNKNOWN_TYPE", 1L, "WHATEVER", BigDecimal.ONE, "USD");
        String body = "{}";
        String sig = sign(ts, body);

        when(eventRepo.existsByEventId("evt-z")).thenReturn(false);

        service.process(payload, body, sig, ts);

        verify(eventRepo).save(any(PspWebhookEvent.class));
        verify(transactionRepo, never()).findById(any());
        verify(publisher, never()).publish(any());
    }

    @Test
    void process_voidEvent_updatesTransactionToVoided() {
        long ts = Instant.now().getEpochSecond();
        PspWebhookPayload payload = new PspWebhookPayload("evt-v", "PAYMENT_VOIDED", 7L, "VOIDED", BigDecimal.TEN, "USD");
        String body = "{}";
        String sig = sign(ts, body);

        Transaction tx = new Transaction();
        tx.setId(7L);
        tx.setMerchantId(1L);
        tx.setUserId(1L);
        tx.setAmount(BigDecimal.TEN);
        tx.setCurrency("USD");
        tx.authorize();

        when(eventRepo.existsByEventId("evt-v")).thenReturn(false);
        when(transactionRepo.findById(7L)).thenReturn(Optional.of(tx));

        service.process(payload, body, sig, ts);

        assertThat(tx.getStatus()).isEqualTo(PaymentStatus.VOIDED.name());
    }

    @Test
    void process_refundEvent_updatesTransactionToRefunded() {
        long ts = Instant.now().getEpochSecond();
        PspWebhookPayload payload = new PspWebhookPayload("evt-r", "PAYMENT_REFUNDED", 9L, "REFUNDED", BigDecimal.TEN, "USD");
        String body = "{}";
        String sig = sign(ts, body);

        Transaction tx = new Transaction();
        tx.setId(9L);
        tx.setMerchantId(1L);
        tx.setUserId(1L);
        tx.setAmount(BigDecimal.TEN);
        tx.setCurrency("USD");
        tx.authorize();
        tx.capture(); // refund requires CAPTURED state

        when(eventRepo.existsByEventId("evt-r")).thenReturn(false);
        when(transactionRepo.findById(9L)).thenReturn(Optional.of(tx));

        service.process(payload, body, sig, ts);

        assertThat(tx.getStatus()).isEqualTo(PaymentStatus.REFUNDED.name());
    }
}
