package com.kimpay.payment;

import com.kimpay.payment.core.repository.TransactionRepository;
import com.kimpay.payment.domain.entity.Transaction;
import org.junit.jupiter.api.Test;
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
class TransactionContextPersistenceTest {

    @Autowired
    private TransactionRepository transactionRepository;

    @Test
    void persistsWalletIdAndPspReference() {
        Transaction t = new Transaction();
        t.setUserId(1L);
        t.setMerchantId(2L);
        t.setAmount(new BigDecimal("10.00"));
        t.setCurrency("USD");
        t.setWalletId(7L);
        t.setPspReference("mock_xyz");
        t.authorize();

        Transaction saved = transactionRepository.save(t);
        Transaction reloaded = transactionRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getWalletId()).isEqualTo(7L);
        assertThat(reloaded.getPspReference()).isEqualTo("mock_xyz");
    }
}
