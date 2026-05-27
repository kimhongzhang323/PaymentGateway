package com.kimpay.payment.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kimpay.payment.core.repository.*;
import com.kimpay.payment.core.service.ApiKeyService;
import com.kimpay.payment.domain.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "payment.encryption.key-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
        "payment.ratelimit.capacity=2",
        "payment.ratelimit.refill-tokens=2",
        "payment.ratelimit.refill-period=1h"
})
class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AsymmetricKeyService asymmetricKeyService;

    @Autowired
    private ApiKeyService apiKeyService;

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
    private KeyPair keyPair;
    private String keyId;
    private String secret;

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
        user.setName("E2E Test User");
        user.setEmail("e2e@test.com");
        user.setPasswordHash("hash");
        user.setRoleId(1L);
        user = userRepository.save(user);
        userId = user.getId();

        keyPair = asymmetricKeyService.generateKeyPair();

        Merchant merchant = new Merchant();
        merchant.setUserId(userId);
        merchant.setBusinessName("E2E Merchant");
        merchant.setStatus("active");
        merchant.setPublicKey(asymmetricKeyService.encodeKey(keyPair.getPublic()));
        merchant = merchantRepository.save(merchant);
        merchantId = merchant.getId();

        var issued = apiKeyService.issueKey(merchantId);
        keyId = issued.keyId();
        secret = issued.secret();

        Wallet wallet = new Wallet();
        wallet.setUserId(userId);
        wallet.setCurrency("USD");
        wallet.setBalance(new BigDecimal("1000.00"));
        wallet = walletRepository.save(wallet);
        walletId = wallet.getId();
    }

    @Test
    void exceedingCapacityReturns429() throws Exception {
        String body = buildPaymentBody("10.00");

        // First two requests consume the bucket capacity of 2.
        signedPost("/api/payments", body, UUID.randomUUID().toString(), keyPair, keyId, secret)
                .andExpect(status().isCreated());
        signedPost("/api/payments", body, UUID.randomUUID().toString(), keyPair, keyId, secret)
                .andExpect(status().isCreated());

        // Third request within the window is denied: no tokens left, refill only after 1h.
        signedPost("/api/payments", body, UUID.randomUUID().toString(), keyPair, keyId, secret)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("SEC-002"))
                .andExpect(header().exists("Retry-After"));
    }

    private String buildPaymentBody(String amount) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "userId", userId,
                "merchantId", merchantId,
                "walletId", walletId,
                "amount", new BigDecimal(amount),
                "currency", "USD"
        ));
    }

    private ResultActions signedPost(String uri, String bodyJson, String nonce,
                                     KeyPair kp, String kId, String sec) throws Exception {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        byte[] body = bodyJson.getBytes(StandardCharsets.UTF_8);
        String bodyHash = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(body));
        String canonical = "POST." + uri + "." + ts + "." + nonce + "." + bodyHash;
        String sig = asymmetricKeyService.sign(canonical, kp.getPrivate());
        return mockMvc.perform(post(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson)
                .header("Authorization", "Bearer " + kId + ":" + sec)
                .header("X-Kimpay-Timestamp", ts)
                .header("X-Kimpay-Nonce", nonce)
                .header("X-Kimpay-Signature", sig));
    }
}
