package com.kimpay.payment.core.dto;

import java.time.LocalDateTime;

/**
 * Read-only projection of a {@link com.kimpay.payment.domain.entity.WebhookEndpoint}
 * for list responses. Deliberately omits the signing secret — the plaintext is shown
 * only once at registration and is never retrievable thereafter.
 */
public record WebhookEndpointView(Long id, String url, boolean enabled, LocalDateTime createdAt) {
}
