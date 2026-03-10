package com.kimpay.payment.controller;

import com.kimpay.payment.core.dto.CreatePaymentRequest;
import com.kimpay.payment.core.dto.PaymentResponse;
import com.kimpay.payment.core.dto.QRPaymentRequest;
import com.kimpay.payment.core.dto.RefundPaymentRequest;
import com.kimpay.payment.core.service.PaymentService;
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

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(@RequestBody CreatePaymentRequest request) {
        PaymentResponse response = paymentService.createPayment(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable Long transactionId) {
        return ResponseEntity.ok(paymentService.getPayment(transactionId));
    }

    @PostMapping("/scan")
    public ResponseEntity<PaymentResponse> scanPayment(@RequestBody QRPaymentRequest request) {
        return ResponseEntity.ok(paymentService.processQRPayment(request));
    }

    @GetMapping("/merchant/{merchantId}/qr")
    public ResponseEntity<String> getMerchantQR(
            @PathVariable Long merchantId,
            @RequestParam BigDecimal amount,
            @RequestParam String currency
    ) {
        return ResponseEntity.ok(paymentService.generateMerchantQRImage(merchantId, amount, currency));
    }

    @PostMapping("/{transactionId}/capture")
    public ResponseEntity<PaymentResponse> capturePayment(@PathVariable Long transactionId) {
        return ResponseEntity.ok(paymentService.capturePayment(transactionId));
    }

    @PostMapping("/{transactionId}/void")
    public ResponseEntity<PaymentResponse> voidPayment(@PathVariable Long transactionId) {
        return ResponseEntity.ok(paymentService.voidPayment(transactionId));
    }

    @PostMapping("/{transactionId}/refund")
    public ResponseEntity<PaymentResponse> refundPayment(
            @PathVariable Long transactionId,
            @RequestBody RefundPaymentRequest request
    ) {
        return ResponseEntity.ok(paymentService.refundPayment(transactionId, request));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<PaymentResponse>> getTransactionsByUser(@PathVariable Long userId) {
        return ResponseEntity.ok(paymentService.getTransactionsByUser(userId));
    }

    @GetMapping("/merchant/{merchantId}")
    public ResponseEntity<List<PaymentResponse>> getTransactionsByMerchant(@PathVariable Long merchantId) {
        return ResponseEntity.ok(paymentService.getTransactionsByMerchant(merchantId));
    }
}
