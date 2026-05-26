package com.kimpay.payment;

import com.kimpay.payment.core.dto.CreatePaymentRequest;
import com.kimpay.payment.core.dto.PaymentResponse;
import com.kimpay.payment.core.dto.RefundPaymentRequest;
import com.kimpay.payment.core.ledger.LedgerService;
import com.kimpay.payment.core.repository.*;
import com.kimpay.payment.core.service.PaymentService;
import com.kimpay.payment.domain.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "payment.encryption.key-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="
})
class LedgerIntegrationTest {

    @Autowired PaymentService paymentService;
    @Autowired LedgerService ledgerService;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired UserRepository userRepository;
    @Autowired MerchantRepository merchantRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired WalletTransactionRepository walletTransactionRepository;
    @Autowired RefundRepository refundRepository;
    @Autowired LedgerAccountRepository ledgerAccountRepository;
    @Autowired JournalEntryRepository journalEntryRepository;
    @Autowired JournalLineRepository journalLineRepository;

    Long userId, merchantId, walletId;

    @BeforeEach
    void setUp() {
        // Clear idempotency keys from Redis to avoid cross-test contamination
        Set<String> idempotencyKeys = redisTemplate.keys("payment:idempotency:*");
        if (idempotencyKeys != null && !idempotencyKeys.isEmpty()) {
            redisTemplate.delete(idempotencyKeys);
        }

        journalLineRepository.deleteAll();
        journalEntryRepository.deleteAll();
        // delete only owner accounts; system accounts are seeded and should persist
        ledgerAccountRepository.findAll().stream()
                .filter(a -> a.getOwnerType() != AccountOwnerType.SYSTEM)
                .forEach(ledgerAccountRepository::delete);

        refundRepository.deleteAll();
        walletTransactionRepository.deleteAll();
        transactionRepository.deleteAll();
        walletRepository.deleteAll();
        merchantRepository.deleteAll();
        userRepository.deleteAll();

        User u = new User(); u.setName("U"); u.setEmail("u@test.com");
        u.setPasswordHash("h"); u.setRoleId(1L);
        userId = userRepository.save(u).getId();
        Merchant m = new Merchant(); m.setUserId(userId);
        m.setBusinessName("M"); m.setStatus("active");
        merchantId = merchantRepository.save(m).getId();
        Wallet w = new Wallet(); w.setUserId(userId);
        w.setCurrency("USD"); w.setBalance(new BigDecimal("100.00"));
        walletId = walletRepository.save(w).getId();
    }

    @Test
    void walletCapturePostsBalancedJournalEntry() {
        CreatePaymentRequest req = new CreatePaymentRequest(
                userId, merchantId, null, walletId, new BigDecimal("30.00"), "USD", true, null);

        paymentService.createPayment(req);

        // One journal entry posted
        assertThat(journalEntryRepository.findAll()).hasSize(1);
        assertThat(journalEntryRepository.findAll().get(0).getEventType()).isEqualTo(EntryEventType.CAPTURE);

        // Two lines (no fee): DR WALLET / CR MERCHANT
        assertThat(journalLineRepository.findAll()).hasSize(2);

        // Trial balance holds
        assertThat(ledgerService.trialBalance()).isTrue();

        // WALLET account: LIABILITY DR reduces balance (debitSign=+1, classSign=-1, delta=-30)
        Optional<LedgerAccount> walletAcc = ledgerAccountRepository
                .findByCodeAndCurrency("WALLET:" + walletId, "USD");
        assertThat(walletAcc).isPresent();
        assertThat(walletAcc.get().getBalance()).isEqualByComparingTo("-30.00");

        // MERCHANT account: LIABILITY CR increases balance (debitSign=-1, classSign=-1, delta=+30)
        Optional<LedgerAccount> merchantAcc = ledgerAccountRepository
                .findByCodeAndCurrency("MERCHANT:" + merchantId, "USD");
        assertThat(merchantAcc).isPresent();
        assertThat(merchantAcc.get().getBalance()).isEqualByComparingTo("30.00");
    }

    @Test
    void refundReversesBalancesAndTrialBalanceHolds() {
        CreatePaymentRequest captureReq = new CreatePaymentRequest(
                userId, merchantId, null, walletId, new BigDecimal("30.00"), "USD", true, null);
        PaymentResponse captured = paymentService.createPayment(captureReq);

        paymentService.refundPayment(captured.id(),
                new RefundPaymentRequest(new BigDecimal("10.00"), "test refund"));

        assertThat(ledgerService.trialBalance()).isTrue();

        // Two entries: one CAPTURE, one REFUND
        assertThat(journalEntryRepository.findAll()).hasSize(2);

        // MERCHANT: 30 CR on capture (+30), 10 DR on refund (-10) → net 20
        LedgerAccount merchantAcc = ledgerAccountRepository
                .findByCodeAndCurrency("MERCHANT:" + merchantId, "USD").orElseThrow();
        assertThat(merchantAcc.getBalance()).isEqualByComparingTo("20.00");

        // WALLET: 30 DR on capture (-30), 10 CR on refund (+10) → net -20
        LedgerAccount walletAcc = ledgerAccountRepository
                .findByCodeAndCurrency("WALLET:" + walletId, "USD").orElseThrow();
        assertThat(walletAcc.getBalance()).isEqualByComparingTo("-20.00");
    }

    @Test
    void trialBalanceHoldsAcrossMultipleCaptures() {
        for (int i = 1; i <= 3; i++) {
            paymentService.createPayment(new CreatePaymentRequest(
                    userId, merchantId, null, walletId,
                    new BigDecimal("10.00"), "USD", true, "key-" + i));
        }
        assertThat(ledgerService.trialBalance()).isTrue();
        assertThat(journalEntryRepository.findAll()).hasSize(3);
    }
}
