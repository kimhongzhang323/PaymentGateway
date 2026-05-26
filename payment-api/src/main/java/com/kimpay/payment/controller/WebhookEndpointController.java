package com.kimpay.payment.controller;

import com.kimpay.payment.core.dto.RegisterWebhookEndpointRequest;
import com.kimpay.payment.core.service.WebhookEndpointService;
import com.kimpay.payment.security.MerchantPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/webhook-endpoints")
@RequiredArgsConstructor
public class WebhookEndpointController {

    private final WebhookEndpointService endpointService;

    @PostMapping
    public ResponseEntity<WebhookEndpointService.WebhookEndpointCreated> register(
            @AuthenticationPrincipal MerchantPrincipal principal,
            @Valid @RequestBody RegisterWebhookEndpointRequest request
    ) {
        WebhookEndpointService.WebhookEndpointCreated created =
                endpointService.register(principal.merchantId(), request);
        return ResponseEntity.status(201).body(created);
    }

    @GetMapping
    public ResponseEntity<List<?>> list(@AuthenticationPrincipal MerchantPrincipal principal) {
        return ResponseEntity.ok(endpointService.list(principal.merchantId()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal MerchantPrincipal principal,
            @PathVariable Long id
    ) {
        endpointService.delete(principal.merchantId(), id);
        return ResponseEntity.noContent().build();
    }
}
