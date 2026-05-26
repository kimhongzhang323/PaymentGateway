package com.kimpay.payment.core.repository;

import com.kimpay.payment.domain.entity.WebhookEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, Long> {
    List<WebhookEndpoint> findByMerchantIdAndEnabledTrue(Long merchantId);
    List<WebhookEndpoint> findByMerchantId(Long merchantId);
}
