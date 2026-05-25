package com.kimpay.payment.controller;

import com.kimpay.payment.core.dto.CreatePaymentRequest;
import com.kimpay.payment.core.dto.PaymentResponse;
import com.kimpay.payment.core.dto.QRPaymentRequest;
import com.kimpay.payment.core.dto.RefundPaymentRequest;
import com.kimpay.payment.core.service.PaymentService;
import com.kimpay.payment.security.AuthorizationGuard;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final AuthorizationGuard authorizationGuard;

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(@Valid @RequestBody CreatePaymentRequest request) {
        authorizationGuard.requireOwnership(request.merchantId());
        PaymentResponse response = paymentService.createPayment(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable Long transactionId) {
        PaymentResponse resp = paymentService.getPayment(transactionId);
        authorizationGuard.requireOwnership(resp.merchantId());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/scan")
    public ResponseEntity<PaymentResponse> scanPayment(@RequestBody QRPaymentRequest request) {
        // TODO(phase1-followup): enforce QR merchant matches authenticated merchant
        return ResponseEntity.ok(paymentService.processQRPayment(request));
    }

    @GetMapping("/merchant/{merchantId}/qr")
    public ResponseEntity<String> getMerchantQR(
            @PathVariable Long merchantId,
            @RequestParam BigDecimal amount,
            @RequestParam String currency
    ) {
        authorizationGuard.requireOwnership(merchantId);
        return ResponseEntity.ok(paymentService.generateMerchantQRImage(merchantId, amount, currency));
    }

    @PostMapping("/{transactionId}/capture")
    public ResponseEntity<PaymentResponse> capturePayment(@PathVariable Long transactionId) {
        PaymentResponse existing = paymentService.getPayment(transactionId);
        authorizationGuard.requireOwnership(existing.merchantId());
        return ResponseEntity.ok(paymentService.capturePayment(transactionId));
    }

    @PostMapping("/{transactionId}/void")
    public ResponseEntity<PaymentResponse> voidPayment(@PathVariable Long transactionId) {
        PaymentResponse existing = paymentService.getPayment(transactionId);
        authorizationGuard.requireOwnership(existing.merchantId());
        return ResponseEntity.ok(paymentService.voidPayment(transactionId));
    }

    @PostMapping("/{transactionId}/refund")
    public ResponseEntity<PaymentResponse> refundPayment(
            @PathVariable Long transactionId,
            @Valid @RequestBody RefundPaymentRequest request
    ) {
        PaymentResponse existing = paymentService.getPayment(transactionId);
        authorizationGuard.requireOwnership(existing.merchantId());
        return ResponseEntity.ok(paymentService.refundPayment(transactionId, request));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<PaymentResponse>> getTransactionsByUser(@PathVariable Long userId) {
        Long me = authorizationGuard.currentMerchantId();
        return ResponseEntity.ok(paymentService.getTransactionsByUser(userId).stream()
                .filter(r -> me.equals(r.merchantId()))
                .toList());
    }

    @GetMapping("/merchant/{merchantId}")
    public ResponseEntity<List<PaymentResponse>> getTransactionsByMerchant(@PathVariable Long merchantId) {
        authorizationGuard.requireOwnership(merchantId);
        return ResponseEntity.ok(paymentService.getTransactionsByMerchant(merchantId));
    }
}
