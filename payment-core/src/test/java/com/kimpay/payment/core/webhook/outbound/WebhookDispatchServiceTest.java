package com.kimpay.payment.core.webhook.outbound;

import com.kimpay.payment.core.repository.WebhookDeliveryRepository;
import com.kimpay.payment.core.repository.WebhookEndpointRepository;
import com.kimpay.payment.core.webhook.HmacSigningService;
import com.kimpay.payment.domain.entity.WebhookDelivery;
import com.kimpay.payment.domain.entity.WebhookDeliveryStatus;
import com.kimpay.payment.domain.entity.WebhookEndpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookDispatchServiceTest {

    @Mock WebhookDeliveryRepository deliveryRepo;
    @Mock WebhookEndpointRepository endpointRepo;
    @Mock RestClient restClient;
    @Mock RestClient.RequestBodyUriSpec requestSpec;
    @Mock RestClient.RequestBodySpec bodySpec;
    @Mock RestClient.ResponseSpec responseSpec;

    HmacSigningService hmac = new HmacSigningService();
    WebhookDispatchService service;

    @BeforeEach
    void setUp() {
        service = new WebhookDispatchService(deliveryRepo, endpointRepo, hmac, restClient);
    }

    private WebhookEndpoint endpoint(String url, String secret) {
        WebhookEndpoint ep = new WebhookEndpoint();
        ep.setId(1L);
        ep.setMerchantId(10L);
        ep.setUrl(url);
        ep.setSecret(secret);
        ep.setEnabled(true);
        return ep;
    }

    private WebhookDelivery delivery(long endpointId, int attempts) {
        WebhookDelivery d = new WebhookDelivery();
        d.setId(100L);
        d.setEndpointId(endpointId);
        d.setEventType("PAYMENT_CAPTURED");
        d.setPayload("{\"event\":\"PAYMENT_CAPTURED\"}");
        d.setStatus(WebhookDeliveryStatus.PENDING.name());
        d.setAttempts(attempts);
        d.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        return d;
    }

    private void stubSuccessfulPost() {
        when(restClient.post()).thenReturn(requestSpec);
        when(requestSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any())).thenReturn(bodySpec);
        when(bodySpec.body(anyString())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());
    }

    private void stubFailingPost(RuntimeException ex) {
        when(restClient.post()).thenReturn(requestSpec);
        when(requestSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any())).thenReturn(bodySpec);
        when(bodySpec.body(anyString())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        doThrow(ex).when(responseSpec).toBodilessEntity();
    }

    @Test
    void dispatch_successResponse_marksDeliverySuccess() {
        WebhookEndpoint ep = endpoint("https://merchant.example.com/hooks", "secret");
        WebhookDelivery del = delivery(1L, 0);

        when(endpointRepo.findById(1L)).thenReturn(Optional.of(ep));
        stubSuccessfulPost();

        service.dispatch(del);

        ArgumentCaptor<WebhookDelivery> cap = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(WebhookDeliveryStatus.SUCCESS.name());
        assertThat(cap.getValue().getAttempts()).isEqualTo(1);
    }

    @Test
    void dispatch_httpError_incrementsAttemptsAndSchedulesRetry() {
        WebhookEndpoint ep = endpoint("https://merchant.example.com/hooks", "secret");
        WebhookDelivery del = delivery(1L, 0);

        when(endpointRepo.findById(1L)).thenReturn(Optional.of(ep));
        stubFailingPost(new HttpClientErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        service.dispatch(del);

        ArgumentCaptor<WebhookDelivery> cap = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED.name());
        assertThat(cap.getValue().getAttempts()).isEqualTo(1);
        assertThat(cap.getValue().getNextRetryAt()).isAfter(LocalDateTime.now());
    }

    @Test
    void dispatch_maxAttemptsReached_marksDeliveryDead() {
        WebhookEndpoint ep = endpoint("https://merchant.example.com/hooks", "secret");
        WebhookDelivery del = delivery(1L, 4); // 4 + 1 = 5 → DEAD

        when(endpointRepo.findById(1L)).thenReturn(Optional.of(ep));
        stubFailingPost(new RuntimeException("timeout"));

        service.dispatch(del);

        ArgumentCaptor<WebhookDelivery> cap = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(WebhookDeliveryStatus.DEAD.name());
        assertThat(cap.getValue().getAttempts()).isEqualTo(5);
    }

    @Test
    void retryDelaySeconds_followsExponentialSchedule() {
        assertThat(WebhookDispatchService.retryDelaySeconds(1)).isEqualTo(30);
        assertThat(WebhookDispatchService.retryDelaySeconds(2)).isEqualTo(300);
        assertThat(WebhookDispatchService.retryDelaySeconds(3)).isEqualTo(1800);
        assertThat(WebhookDispatchService.retryDelaySeconds(4)).isEqualTo(7200);
        assertThat(WebhookDispatchService.retryDelaySeconds(5)).isEqualTo(28800);
    }
}
