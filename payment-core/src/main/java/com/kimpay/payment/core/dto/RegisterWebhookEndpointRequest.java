package com.kimpay.payment.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RegisterWebhookEndpointRequest(
        @NotBlank
        @Pattern(regexp = "https://.*", message = "URL must use HTTPS")
        String url
) {}
