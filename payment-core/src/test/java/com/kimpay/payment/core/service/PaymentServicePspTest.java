package com.kimpay.payment.core.service;

import com.kimpay.payment.core.dto.CreatePaymentRequest;
import com.kimpay.payment.core.dto.PaymentResponse;
import com.kimpay.payment.core.dto.RefundPaymentRequest;
import com.kimpay.payment.core.psp.*;
import com.kimpay.payment.core.repository.*;
import com.kimpay.payment.domain.entity.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentServicePspTest {

    private PspConnector psp;
    private TransactionRepository txnRepo;
    private PaymentMethodRepository methodRepo;
    private RefundRepository refundRepo;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        psp = mock(PspConnector.class);
        txnRepo = mock(TransactionRepository.class);
        var walletRepo = mock(WalletRepository.class);
        var walletTxnRepo = mock(WalletTransactionRepository.class);
        methodRepo = mock(PaymentMethodRepository.class);
        refundRepo = mock(RefundRepository.class);
        var logRepo = mock(TransactionLogRepository.class);
        var userRepo = mock(UserRepository.class);
        var merchantRepo = mock(MerchantRepository.class);
        var publisher = mock(com.kimpay.payment.core.event.PaymentEventPublisher.class);
        var encryption = mock(com.kimpay.payment.security.EncryptionService.class);
        var qr = mock(QRService.class);
        var redis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        var opsForValue = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(opsForValue);
        var redisson = mock(org.redisson.api.RedissonClient.class);

        service = new PaymentService(txnRepo, walletRepo, walletTxnRepo, methodRepo, refundRepo,
                logRepo, userRepo, merchantRepo, publisher, encryption, qr, redis, redisson, psp);

        when(userRepo.existsById(anyLong())).thenReturn(true);
        when(merchantRepo.existsById(anyLong())).thenReturn(true);
        when(redis.hasKey(anyString())).thenReturn(false);
        when(txnRepo.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            if (t.getId() == null) t.setId(100L);
            return t;
        });
        var method = new com.kimpay.payment.domain.entity.PaymentMethod();
        method.setStatus("active");
        when(methodRepo.findByIdAndUserId(anyLong(), anyLong())).thenReturn(java.util.Optional.of(method));
    }

    @Test
    void cardPaymentAuthorizesThroughPspAndStoresReference() {
        when(psp.authorize(any())).thenReturn(PspResult.ok(PspStatus.CAPTURED, "mock_ref_1"));
        CreatePaymentRequest req = new CreatePaymentRequest(1L, 2L, 9L, null,
                new BigDecimal("10.00"), "USD", true, null);

        PaymentResponse resp = service.createPayment(req);

        ArgumentCaptor<PspAuthorizeRequest> cap = ArgumentCaptor.forClass(PspAuthorizeRequest.class);
        verify(psp).authorize(cap.capture());
        assertThat(cap.getValue().amount()).isEqualByComparingTo("10.00");
        assertThat(cap.getValue().capture()).isTrue();
        assertThat(resp.status()).isEqualTo("CAPTURED");

        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(txnRepo, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).anyMatch(t -> "mock_ref_1".equals(t.getPspReference()));
    }

    @Test
    void voidingCardTransactionCallsPspVoid() {
        Transaction t = new Transaction();
        t.setId(50L); t.setUserId(1L); t.setMerchantId(2L);
        t.setAmount(new BigDecimal("10.00")); t.setCurrency("USD");
        t.setPspReference("mock_ref_v"); // card-backed: walletId stays null
        t.authorize();
        when(txnRepo.findById(50L)).thenReturn(java.util.Optional.of(t));
        when(psp.voidAuthorization("mock_ref_v")).thenReturn(PspResult.ok(PspStatus.VOIDED, "mock_ref_v"));

        service.voidPayment(50L);

        verify(psp).voidAuthorization("mock_ref_v");
    }

    @Test
    void refundingCardTransactionCallsPspRefund() {
        Transaction t = new Transaction();
        t.setId(51L); t.setUserId(1L); t.setMerchantId(2L);
        t.setAmount(new BigDecimal("10.00")); t.setCurrency("USD");
        t.setPspReference("mock_ref_r"); // card-backed
        t.authorize(); t.capture();
        when(txnRepo.findById(51L)).thenReturn(java.util.Optional.of(t));
        when(refundRepo.findAllByTransactionId(51L)).thenReturn(java.util.List.of());
        when(psp.refund(eq("mock_ref_r"), any())).thenReturn(PspResult.ok(PspStatus.REFUNDED, "mock_ref_r"));

        service.refundPayment(51L, new RefundPaymentRequest(new BigDecimal("10.00"), "x"));

        verify(psp).refund(eq("mock_ref_r"), any());
    }

    @Test
    void declinedCardPaymentMarksTransactionFailed() {
        when(psp.authorize(any())).thenReturn(PspResult.declined("mock_ref_2", "Card declined (mock)"));
        CreatePaymentRequest req = new CreatePaymentRequest(1L, 2L, 9L, null,
                new BigDecimal("10.00"), "USD", true, null);

        assertThatThrownBy(() -> service.createPayment(req))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(txnRepo, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).anyMatch(t -> "FAILED".equals(t.getStatus()));
    }
}
