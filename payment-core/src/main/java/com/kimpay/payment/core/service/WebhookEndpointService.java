package com.kimpay.payment.core.service;

import com.kimpay.payment.core.dto.RegisterWebhookEndpointRequest;
import com.kimpay.payment.core.repository.WebhookEndpointRepository;
import com.kimpay.payment.domain.entity.WebhookEndpoint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WebhookEndpointService {

    private final WebhookEndpointRepository endpointRepository;

    @Transactional
    public WebhookEndpointCreated register(Long merchantId, RegisterWebhookEndpointRequest request) {
        String secret = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");

        WebhookEndpoint ep = new WebhookEndpoint();
        ep.setMerchantId(merchantId);
        ep.setUrl(request.url());
        ep.setSecret(secret);
        ep.setEnabled(true);
        endpointRepository.save(ep);

        return new WebhookEndpointCreated(ep.getId(), ep.getUrl(), secret);
    }

    @Transactional(readOnly = true)
    public List<WebhookEndpoint> list(Long merchantId) {
        return endpointRepository.findByMerchantId(merchantId);
    }

    @Transactional
    public void delete(Long merchantId, Long endpointId) {
        WebhookEndpoint ep = endpointRepository.findById(endpointId)
                .orElseThrow(() -> new IllegalArgumentException("Endpoint not found: " + endpointId));
        if (!ep.getMerchantId().equals(merchantId)) {
            throw new IllegalArgumentException("Endpoint not found or not yours");
        }
        endpointRepository.delete(ep);
    }

    public record WebhookEndpointCreated(Long id, String url, String secret) {}
}
