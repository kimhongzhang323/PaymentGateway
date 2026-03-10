package com.kimpay.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kimpay.payment.core.repository.*;
import com.kimpay.payment.domain.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(properties = {
        "payment.encryption.key-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="
})
class PaymentControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private PaymentMethodRepository paymentMethodRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private WalletTransactionRepository walletTransactionRepository;

    @Autowired
    private RefundRepository refundRepository;

    private Long userId;
    private Long merchantId;
    private Long walletId;

    @BeforeEach
    void setUp() {
        refundRepository.deleteAll();
        walletTransactionRepository.deleteAll();
        transactionRepository.deleteAll();
        paymentMethodRepository.deleteAll();
        walletRepository.deleteAll();
        merchantRepository.deleteAll();
        userRepository.deleteAll();

        User user = new User();
        user.setName("Test User");
        user.setEmail("user@test.com");
        user.setPasswordHash("hash");
        user.setRoleId(1L);
        user = userRepository.save(user);
        userId = user.getId();

        Merchant merchant = new Merchant();
        merchant.setUserId(userId);
        merchant.setBusinessName("Test Merchant");
        merchant.setStatus("active");
        merchant = merchantRepository.save(merchant);
        merchantId = merchant.getId();

        Wallet wallet = new Wallet();
        wallet.setUserId(userId);
        wallet.setCurrency("USD");
        wallet.setBalance(new BigDecimal("100.00"));
        wallet = walletRepository.save(wallet);
        walletId = wallet.getId();
    }

    @Test
    void shouldCreateAndFetchWalletPayment() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "userId", userId,
                "merchantId", merchantId,
                "walletId", walletId,
                "amount", new BigDecimal("25.00"),
                "currency", "USD"
        ));

        String body = mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        Long transactionId = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(get("/api/payments/{id}", transactionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transactionId))
                .andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    @Test
    void shouldRefundCapturedWalletPayment() throws Exception {
        String paymentPayload = objectMapper.writeValueAsString(Map.of(
                "userId", userId,
                "merchantId", merchantId,
                "walletId", walletId,
                "amount", new BigDecimal("10.00"),
                "currency", "USD"
        ));

        String paymentBody = mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentPayload))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Long transactionId = objectMapper.readTree(paymentBody).get("id").asLong();

        String refundPayload = objectMapper.writeValueAsString(Map.of(
                "amount", new BigDecimal("10.00"),
                "reason", "Customer request"
        ));

        mockMvc.perform(post("/api/payments/{id}/refund", transactionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
    }
}
