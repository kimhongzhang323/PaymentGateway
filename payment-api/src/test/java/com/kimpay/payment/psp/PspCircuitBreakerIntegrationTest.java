package com.kimpay.payment.psp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kimpay.payment.core.repository.MerchantRepository;
import com.kimpay.payment.core.repository.PaymentMethodRepository;
import com.kimpay.payment.core.repository.RefundRepository;
import com.kimpay.payment.core.repository.TransactionRepository;
import com.kimpay.payment.core.repository.UserRepository;
import com.kimpay.payment.core.repository.WalletRepository;
import com.kimpay.payment.core.repository.WalletTransactionRepository;
import com.kimpay.payment.core.service.ApiKeyService;
import com.kimpay.payment.domain.entity.Merchant;
import com.kimpay.payment.domain.entity.PaymentMethod;
import com.kimpay.payment.domain.entity.User;
import com.kimpay.payment.security.AsymmetricKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves that when the PSP delegate fails repeatedly, the Resilience4j circuit breaker
 * opens and the API returns a graceful 503 / NET-003 (with Retry-After) instead of a 500,
 * and that no internal detail (the delegate's exception message, class names, stack frames)
 * leaks into the error body.
 *
 * <p>The {@link FailingPspConfig} registers a bean named {@code "pspDelegate"} that always
 * throws; this suppresses the production mock (guarded by
 * {@code @ConditionalOnMissingBean(name="pspDelegate")}) and is wrapped by the @Primary
 * {@code resilientPspConnector}. The resilience window is shrunk via @TestPropertySource so
 * two failures trip the breaker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PspCircuitBreakerIntegrationTest.FailingPspConfig.class)
@TestPropertySource(properties = {
        "payment.encryption.key-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
        // The @ConditionalOnMissingBean(name="pspDelegate") mock and our @TestConfiguration
        // delegate share the bean name "pspDelegate"; the condition fires before override
        // resolution, so allow overriding to let our failing delegate win.
        "spring.main.allow-bean-definition-overriding=true",
        "payment.psp.resilience.sliding-window-size=2",
        "payment.psp.resilience.minimum-number-of-calls=2",
        "payment.psp.resilience.failure-rate-threshold=50",
        "payment.psp.resilience.wait-duration-in-open-state=30s",
        "payment.psp.resilience.timeout=3s"
})
class PspCircuitBreakerIntegrationTest {

    @TestConfiguration
    static class FailingPspConfig {
        @org.springframework.context.annotation.Bean("pspDelegate")
        @org.springframework.beans.factory.annotation.Qualifier("pspDelegate")
        com.kimpay.payment.core.psp.PspConnector failingDelegate() {
            return new com.kimpay.payment.core.psp.PspConnector() {
                public com.kimpay.payment.core.psp.PspResult authorize(com.kimpay.payment.core.psp.PspAuthorizeRequest r) {
                    throw new RuntimeException("psp down");
                }
                public com.kimpay.payment.core.psp.PspResult capture(String ref, java.math.BigDecimal a) {
                    throw new RuntimeException("psp down");
                }
                public com.kimpay.payment.core.psp.PspResult voidAuthorization(String ref) {
                    throw new RuntimeException("psp down");
                }
                public com.kimpay.payment.core.psp.PspResult refund(String ref, java.math.BigDecimal a) {
                    throw new RuntimeException("psp down");
                }
            };
        }
    }

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
    private Long paymentMethodId;
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
        user.setName("CB Test User");
        user.setEmail("cb@test.com");
        user.setPasswordHash("hash");
        user.setRoleId(1L);
        user = userRepository.save(user);
        userId = user.getId();

        keyPair = asymmetricKeyService.generateKeyPair();

        Merchant merchant = new Merchant();
        merchant.setUserId(userId);
        merchant.setBusinessName("CB Merchant");
        merchant.setStatus("active");
        merchant.setPublicKey(asymmetricKeyService.encodeKey(keyPair.getPublic()));
        merchant = merchantRepository.save(merchant);
        merchantId = merchant.getId();

        var issued = apiKeyService.issueKey(merchantId);
        keyId = issued.keyId();
        secret = issued.secret();

        PaymentMethod method = new PaymentMethod();
        method.setUserId(userId);
        method.setType("card");
        method.setProvider("mock");
        method.setStatus("active");
        method = paymentMethodRepository.save(method);
        paymentMethodId = method.getId();
    }

    @Test
    void breakerOpensAndReturns503NotInternalError() throws Exception {
        String body = buildPaymentBody("10.00");

        // First minimumNumberOfCalls (2) failures train the breaker; fire 4 so the last
        // call(s) short-circuit with the breaker OPEN. Don't assert on intermediate
        // statuses (a raw delegate failure before the breaker opens may surface as 500 —
        // outside this slice's scope).
        ResultActions last = null;
        for (int i = 0; i < 4; i++) {
            try {
                last = signedPost("/api/payments", body, UUID.randomUUID().toString());
            } catch (Exception ignored) {
                // Don't let an early delegate exception fail the test before the loop
                // reaches the open-breaker state.
            }
        }

        assertThat(last).as("at least one request must have completed").isNotNull();

        last.andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("NET-003"))
                .andExpect(header().exists("Retry-After"));

        String responseBody = last.andReturn().getResponse().getContentAsString();
        assertThat(responseBody)
                .doesNotContain("psp down")
                .doesNotContain("Exception")
                .doesNotContain("java.");
    }

    private String buildPaymentBody(String amount) throws Exception {
        // CARD body: paymentMethodId set, walletId null. Use a HashMap so a null
        // walletId is serialized explicitly (Map.of rejects nulls).
        Map<String, Object> body = new HashMap<>();
        body.put("userId", userId);
        body.put("merchantId", merchantId);
        body.put("paymentMethodId", paymentMethodId);
        body.put("walletId", null);
        body.put("amount", new BigDecimal(amount));
        body.put("currency", "USD");
        body.put("capture", true);
        return objectMapper.writeValueAsString(body);
    }

    private ResultActions signedPost(String uri, String bodyJson, String nonce) throws Exception {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        byte[] body = bodyJson.getBytes(StandardCharsets.UTF_8);
        String bodyHash = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(body));
        String canonical = "POST." + uri + "." + ts + "." + nonce + "." + bodyHash;
        String sig = asymmetricKeyService.sign(canonical, keyPair.getPrivate());
        return mockMvc.perform(post(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson)
                .header("Authorization", "Bearer " + keyId + ":" + secret)
                .header("X-Kimpay-Timestamp", ts)
                .header("X-Kimpay-Nonce", nonce)
                .header("X-Kimpay-Signature", sig));
    }
}
