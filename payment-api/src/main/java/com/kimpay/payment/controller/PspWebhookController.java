package com.kimpay.payment.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kimpay.payment.core.webhook.inbound.PspWebhookPayload;
import com.kimpay.payment.core.webhook.inbound.PspWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
public class PspWebhookController {

    private final PspWebhookService pspWebhookService;
    private final ObjectMapper objectMapper;

    @PostMapping("/psp")
    public ResponseEntity<Void> receivePspWebhook(
            @RequestHeader("X-Kimpay-Signature") String signature,
            @RequestHeader("X-Kimpay-Timestamp") long timestamp,
            @RequestBody String rawBody
    ) {
        PspWebhookPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, PspWebhookPayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed webhook payload");
        }
        pspWebhookService.process(payload, rawBody, signature, timestamp);
        return ResponseEntity.ok().build();
    }
}
