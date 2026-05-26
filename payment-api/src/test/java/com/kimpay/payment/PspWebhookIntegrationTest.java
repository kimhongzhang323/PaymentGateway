package com.kimpay.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kimpay.payment.core.repository.PspWebhookEventRepository;
import com.kimpay.payment.core.repository.TransactionRepository;
import com.kimpay.payment.core.webhook.HmacSigningService;
import com.kimpay.payment.domain.entity.PaymentStatus;
import com.kimpay.payment.domain.entity.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "payment.encryption.key-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
        "payment.webhook.psp-secret=test-psp-webhook-secret"
})
class PspWebhookIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TransactionRepository transactionRepository;
    @Autowired PspWebhookEventRepository webhookEventRepository;

    HmacSigningService hmac = new HmacSigningService();
    private static final String SECRET = "test-psp-webhook-secret";

    Transaction savedTx;

    @BeforeEach
    void setUp() {
        webhookEventRepository.deleteAll();
        transactionRepository.deleteAll();

        Transaction tx = new Transaction();
        tx.setUserId(1L);
        tx.setMerchantId(1L);
        tx.setAmount(new BigDecimal("50.00"));
        tx.setCurrency("USD");
        tx.authorize();
        savedTx = transactionRepository.save(tx);
    }

    @Test
    void validCapture_updatesTransactionAndPersistsEvent() throws Exception {
        long ts = Instant.now().getEpochSecond();
        String body = objectMapper.writeValueAsString(Map.of(
                "event_id", "evt-cap-1",
                "event_type", "PAYMENT_CAPTURED",
                "transaction_id", savedTx.getId(),
                "status", "CAPTURED",
                "amount", "50.00",
                "currency", "USD"
        ));
        String sig = "sha256=" + hmac.sign(ts + "." + body, SECRET);

        mockMvc.perform(post("/api/webhooks/psp")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Kimpay-Signature", sig)
                .header("X-Kimpay-Timestamp", ts)
                .content(body))
                .andExpect(status().isOk());

        Transaction updated = transactionRepository.findById(savedTx.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(PaymentStatus.CAPTURED.name());
        assertThat(webhookEventRepository.existsByEventId("evt-cap-1")).isTrue();
    }

    @Test
    void duplicateEventId_isNoOpAndReturns200() throws Exception {
        long ts = Instant.now().getEpochSecond();
        String body = objectMapper.writeValueAsString(Map.of(
                "event_id", "evt-dup",
                "event_type", "PAYMENT_CAPTURED",
                "transaction_id", savedTx.getId(),
                "status", "CAPTURED",
                "amount", "50.00",
                "currency", "USD"
        ));
        String sig = "sha256=" + hmac.sign(ts + "." + body, SECRET);

        // First call
        mockMvc.perform(post("/api/webhooks/psp")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Kimpay-Signature", sig)
                .header("X-Kimpay-Timestamp", ts)
                .content(body))
                .andExpect(status().isOk());

        // Second call — same event_id, must be idempotent
        mockMvc.perform(post("/api/webhooks/psp")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Kimpay-Signature", sig)
                .header("X-Kimpay-Timestamp", ts)
                .content(body))
                .andExpect(status().isOk());

        // Still CAPTURED, not double-applied
        assertThat(transactionRepository.findById(savedTx.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CAPTURED.name());
    }

    @Test
    void invalidSignature_returns400() throws Exception {
        long ts = Instant.now().getEpochSecond();
        String body = String.format(
                "{\"event_id\":\"evt-bad\",\"event_type\":\"PAYMENT_CAPTURED\",\"transaction_id\":%d,\"status\":\"CAPTURED\",\"amount\":\"10\",\"currency\":\"USD\"}",
                savedTx.getId());

        mockMvc.perform(post("/api/webhooks/psp")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Kimpay-Signature", "sha256=badhex")
                .header("X-Kimpay-Timestamp", ts)
                .content(body))
                .andExpect(status().isBadRequest());
    }
}
