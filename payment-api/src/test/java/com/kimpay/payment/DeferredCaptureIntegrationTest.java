package com.kimpay.payment;

import com.kimpay.payment.core.dto.CreatePaymentRequest;
import com.kimpay.payment.core.dto.PaymentResponse;
import com.kimpay.payment.core.repository.*;
import com.kimpay.payment.core.service.PaymentService;
import com.kimpay.payment.domain.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "payment.encryption.key-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="
})
class DeferredCaptureIntegrationTest {

    @Autowired PaymentService paymentService;
    @Autowired UserRepository userRepository;
    @Autowired MerchantRepository merchantRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired WalletTransactionRepository walletTransactionRepository;
    @Autowired RefundRepository refundRepository;

    Long userId, merchantId, walletId;

    @BeforeEach
    void setUp() {
        refundRepository.deleteAll();
        walletTransactionRepository.deleteAll();
        transactionRepository.deleteAll();
        walletRepository.deleteAll();
        merchantRepository.deleteAll();
        userRepository.deleteAll();

        User u = new User(); u.setName("U"); u.setEmail("u@test.com"); u.setPasswordHash("h"); u.setRoleId(1L);
        userId = userRepository.save(u).getId();
        Merchant m = new Merchant(); m.setUserId(userId); m.setBusinessName("M"); m.setStatus("active");
        merchantId = merchantRepository.save(m).getId();
        Wallet w = new Wallet(); w.setUserId(userId); w.setCurrency("USD"); w.setBalance(new BigDecimal("100.00"));
        walletId = walletRepository.save(w).getId();
    }

    @Test
    void deferredWalletCaptureDebitsTheStoredWallet() {
        CreatePaymentRequest authReq = new CreatePaymentRequest(
                userId, merchantId, null, walletId, new BigDecimal("25.00"), "USD", false, null);
        PaymentResponse authorized = paymentService.createPayment(authReq);
        assertThat(authorized.status()).isEqualTo("AUTHORIZED");
        assertThat(walletRepository.findById(walletId).orElseThrow().getBalance()).isEqualByComparingTo("100.00");

        PaymentResponse captured = paymentService.capturePayment(authorized.id());
        assertThat(captured.status()).isEqualTo("CAPTURED");
        assertThat(walletRepository.findById(walletId).orElseThrow().getBalance()).isEqualByComparingTo("75.00");
    }
}
