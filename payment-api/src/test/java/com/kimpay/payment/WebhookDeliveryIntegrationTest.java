package com.kimpay.payment;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kimpay.payment.core.repository.MerchantRepository;
import com.kimpay.payment.core.repository.UserRepository;
import com.kimpay.payment.core.repository.WebhookDeliveryRepository;
import com.kimpay.payment.core.repository.WebhookEndpointRepository;
import com.kimpay.payment.core.webhook.outbound.WebhookDispatchService;
import com.kimpay.payment.domain.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "payment.encryption.key-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
        "payment.webhook.psp-secret=test-psp-webhook-secret"
})
class WebhookDeliveryIntegrationTest {

    static WireMockServer wireMock = new WireMockServer(wireMockConfig().dynamicPort());

    @BeforeAll
    static void startWireMock() { wireMock.start(); }

    @AfterAll
    static void stopWireMock() { wireMock.stop(); }

    @BeforeEach
    void resetWireMock() { wireMock.resetAll(); }

    @Autowired WebhookEndpointRepository endpointRepo;
    @Autowired WebhookDeliveryRepository deliveryRepo;
    @Autowired WebhookDispatchService dispatchService;
    @Autowired MerchantRepository merchantRepository;
    @Autowired UserRepository userRepository;

    private Long merchantId;

    @BeforeEach
    void setUp() {
        deliveryRepo.deleteAll();
        endpointRepo.deleteAll();
        merchantRepository.deleteAll();
        userRepository.deleteAll();

        User user = new User();
        user.setName("Webhook Test User");
        user.setEmail("webhook@test.com");
        user.setPasswordHash("hash");
        user.setRoleId(1L);
        user = userRepository.save(user);

        Merchant merchant = new Merchant();
        merchant.setUserId(user.getId());
        merchant.setBusinessName("Webhook Test Merchant");
        merchant.setStatus("active");
        merchant = merchantRepository.save(merchant);
        merchantId = merchant.getId();
    }

    @Test
    void dispatch_successfulDelivery_marksSuccess() {
        wireMock.stubFor(post(urlEqualTo("/hooks"))
                .willReturn(aResponse().withStatus(200)));

        WebhookEndpoint ep = new WebhookEndpoint();
        ep.setMerchantId(merchantId);
        ep.setUrl("http://localhost:" + wireMock.port() + "/hooks");
        ep.setSecret("test-endpoint-secret");
        ep.setEnabled(true);
        endpointRepo.save(ep);

        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setEndpointId(ep.getId());
        delivery.setEventType("PAYMENT_CAPTURED");
        delivery.setPayload("{\"event\":\"PAYMENT_CAPTURED\"}");
        delivery.setStatus(WebhookDeliveryStatus.PENDING.name());
        delivery.setAttempts(0);
        delivery.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        deliveryRepo.save(delivery);

        dispatchService.dispatch(delivery);

        WebhookDelivery updated = deliveryRepo.findById(delivery.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(WebhookDeliveryStatus.SUCCESS.name());
        assertThat(updated.getAttempts()).isEqualTo(1);

        wireMock.verify(postRequestedFor(urlEqualTo("/hooks"))
                .withHeader("X-Kimpay-Signature", matching("sha256=[0-9a-f]{64}"))
                .withHeader("X-Kimpay-Timestamp", matching("\\d+")));
    }

    @Test
    void dispatch_serverError_marksFailedAndSchedulesRetry() {
        wireMock.stubFor(post(urlEqualTo("/hooks"))
                .willReturn(aResponse().withStatus(500)));

        WebhookEndpoint ep = new WebhookEndpoint();
        ep.setMerchantId(merchantId);
        ep.setUrl("http://localhost:" + wireMock.port() + "/hooks");
        ep.setSecret("test-endpoint-secret");
        ep.setEnabled(true);
        endpointRepo.save(ep);

        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setEndpointId(ep.getId());
        delivery.setEventType("PAYMENT_CAPTURED");
        delivery.setPayload("{\"event\":\"PAYMENT_CAPTURED\"}");
        delivery.setStatus(WebhookDeliveryStatus.PENDING.name());
        delivery.setAttempts(0);
        delivery.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        deliveryRepo.save(delivery);

        dispatchService.dispatch(delivery);

        WebhookDelivery updated = deliveryRepo.findById(delivery.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED.name());
        assertThat(updated.getAttempts()).isEqualTo(1);
        assertThat(updated.getNextRetryAt()).isAfter(LocalDateTime.now());
    }

    @Test
    void dispatch_maxAttemptsExceeded_marksDeliveryDead() {
        wireMock.stubFor(post(urlEqualTo("/hooks"))
                .willReturn(aResponse().withStatus(500)));

        WebhookEndpoint ep = new WebhookEndpoint();
        ep.setMerchantId(merchantId);
        ep.setUrl("http://localhost:" + wireMock.port() + "/hooks");
        ep.setSecret("test-endpoint-secret");
        ep.setEnabled(true);
        endpointRepo.save(ep);

        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setEndpointId(ep.getId());
        delivery.setEventType("PAYMENT_CAPTURED");
        delivery.setPayload("{\"event\":\"PAYMENT_CAPTURED\"}");
        delivery.setStatus(WebhookDeliveryStatus.FAILED.name());
        delivery.setAttempts(4); // 4 + 1 = 5 -> DEAD
        delivery.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        deliveryRepo.save(delivery);

        dispatchService.dispatch(delivery);

        WebhookDelivery updated = deliveryRepo.findById(delivery.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(WebhookDeliveryStatus.DEAD.name());
        assertThat(updated.getAttempts()).isEqualTo(5);
    }
}
