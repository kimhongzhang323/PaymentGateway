package com.kimpay.payment.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kimpay.payment.core.repository.*;
import com.kimpay.payment.domain.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "payment.encryption.key-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="
})
class AuthorizationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired MerchantRepository merchantRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired WalletTransactionRepository walletTransactionRepository;
    @Autowired RefundRepository refundRepository;

    Long userId, merchantA, merchantB, walletId, txnOfB;

    @BeforeEach
    void setUp() {
        refundRepository.deleteAll();
        walletTransactionRepository.deleteAll();
        transactionRepository.deleteAll();
        walletRepository.deleteAll();
        merchantRepository.deleteAll();
        userRepository.deleteAll();

        User u = new User();
        u.setName("U"); u.setEmail("u@test.com"); u.setPasswordHash("h"); u.setRoleId(1L);
        userId = userRepository.save(u).getId();

        Merchant a = new Merchant(); a.setUserId(userId); a.setBusinessName("A"); a.setStatus("active");
        merchantA = merchantRepository.save(a).getId();
        Merchant b = new Merchant(); b.setUserId(userId); b.setBusinessName("B"); b.setStatus("active");
        merchantB = merchantRepository.save(b).getId();

        Wallet w = new Wallet(); w.setUserId(userId); w.setCurrency("USD"); w.setBalance(new BigDecimal("100.00"));
        walletId = walletRepository.save(w).getId();

        // a captured transaction owned by merchant B
        Transaction t = new Transaction();
        t.setUserId(userId); t.setMerchantId(merchantB); t.setAmount(new BigDecimal("10.00")); t.setCurrency("USD");
        t.authorize();
        t.capture();
        txnOfB = transactionRepository.save(t).getId();
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private void authenticateAs(Long merchantId) {
        var auth = new UsernamePasswordAuthenticationToken(
                new MerchantPrincipal(merchantId, "pk_test_x"), null,
                List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void merchantCannotReadAnotherMerchantsTransaction() throws Exception {
        authenticateAs(merchantA);
        mockMvc.perform(get("/api/payments/{id}", txnOfB))
                .andExpect(status().isNotFound());
    }

    @Test
    void merchantCannotRefundAnotherMerchantsTransaction() throws Exception {
        authenticateAs(merchantA);
        mockMvc.perform(post("/api/payments/{id}/refund", txnOfB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", new BigDecimal("1.00"), "reason", "x"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void merchantCanReadItsOwnTransaction() throws Exception {
        authenticateAs(merchantB);
        mockMvc.perform(get("/api/payments/{id}", txnOfB))
                .andExpect(status().isOk());
    }

    @Test
    void merchantCannotListAnotherMerchantsTransactions() throws Exception {
        authenticateAs(merchantA);
        mockMvc.perform(get("/api/payments/merchant/{id}", merchantB))
                .andExpect(status().isNotFound());
    }
}
