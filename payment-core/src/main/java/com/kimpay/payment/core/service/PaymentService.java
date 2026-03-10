package com.kimpay.payment.core.service;

import com.kimpay.payment.core.dto.CreatePaymentRequest;
import com.kimpay.payment.core.dto.PaymentResponse;
import com.kimpay.payment.core.dto.RefundPaymentRequest;
import com.kimpay.payment.core.event.PaymentEvent;
import com.kimpay.payment.core.event.PaymentEventPublisher;
import com.kimpay.payment.core.repository.*;
import com.kimpay.payment.domain.entity.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final String WALLET_DEBIT = "DEBIT";
    private static final String WALLET_CREDIT = "CREDIT";

    private final TransactionRepository transactionRepository;
    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final RefundRepository refundRepository;
    private final TransactionLogRepository transactionLogRepository;
    private final UserRepository userRepository;
    private final MerchantRepository merchantRepository;
    private final PaymentEventPublisher paymentEventPublisher;

    @Transactional
    public PaymentResponse createPayment(CreatePaymentRequest request) {
        validateCreateRequest(request);

        if (!userRepository.existsById(request.userId())) {
            throw new IllegalArgumentException("User not found: " + request.userId());
        }
        if (!merchantRepository.existsById(request.merchantId())) {
            throw new IllegalArgumentException("Merchant not found: " + request.merchantId());
        }

        Transaction transaction = new Transaction();
        transaction.setUserId(request.userId());
        transaction.setMerchantId(request.merchantId());
        transaction.setPaymentMethodId(request.paymentMethodId());
        transaction.setAmount(request.amount());
        transaction.setCurrency(normalizeCurrency(request.currency()));
        transaction.authorize();

        transaction = transactionRepository.save(transaction);
        logEvent(transaction.getId(), "AUTHORIZED", "Transaction authorized");
        publishEvent("AUTHORIZED", transaction, "Transaction authorized");

        try {
            if (request.walletId() != null) {
                processWalletDebit(request, transaction);
            } else {
                processPaymentMethod(request);
            }

            transaction.capture();
            transaction = transactionRepository.save(transaction);
            logEvent(transaction.getId(), "CAPTURED", "Transaction captured");
            publishEvent("CAPTURED", transaction, "Transaction captured");
            return PaymentResponse.from(transaction);
        } catch (RuntimeException ex) {
            transaction.markFailed();
            transactionRepository.save(transaction);
            logEvent(transaction.getId(), "FAILED", ex.getMessage());
            publishEvent("FAILED", transaction, ex.getMessage());
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(Long transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));
        return PaymentResponse.from(transaction);
    }

    @Transactional
    public PaymentResponse refundPayment(Long transactionId, RefundPaymentRequest request) {
        if (request == null || request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Refund amount must be greater than zero");
        }

        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));

        if (!PaymentStatus.CAPTURED.name().equals(transaction.getStatus())) {
            throw new IllegalStateException("Only CAPTURED transactions can be refunded");
        }

        if (request.amount().compareTo(transaction.getAmount()) != 0) {
            throw new IllegalArgumentException("Only full refunds are supported for now");
        }

        Refund refund = new Refund();
        refund.setTransactionId(transactionId);
        refund.setAmount(request.amount());
        refund.setReason(request.reason());
        refund.setStatus("COMPLETED");
        refundRepository.save(refund);

        walletTransactionRepository.findFirstByTransactionIdAndTypeOrderByCreatedAtDesc(transactionId, WALLET_DEBIT)
                .ifPresent(debit -> {
                    Wallet wallet = walletRepository.findById(debit.getWalletId())
                            .orElseThrow(() -> new IllegalStateException("Wallet not found for refund"));
                    wallet.setBalance(wallet.getBalance().add(request.amount()));
                    walletRepository.save(wallet);

                    WalletTransaction credit = new WalletTransaction();
                    credit.setWalletId(wallet.getId());
                    credit.setType(WALLET_CREDIT);
                    credit.setAmount(request.amount());
                    credit.setTransactionId(transactionId);
                    walletTransactionRepository.save(credit);
                });

        transaction.refund();
        transaction = transactionRepository.save(transaction);
        logEvent(transactionId, "REFUNDED", "Transaction refunded");
        publishEvent("REFUNDED", transaction, request.reason());

        return PaymentResponse.from(transaction);
    }

    private void processWalletDebit(CreatePaymentRequest request, Transaction transaction) {
        Wallet wallet = walletRepository.findByIdAndUserId(request.walletId(), request.userId())
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for user"));

        String normalizedCurrency = normalizeCurrency(request.currency());
        if (!normalizedCurrency.equalsIgnoreCase(wallet.getCurrency())) {
            throw new IllegalArgumentException("Wallet currency does not match transaction currency");
        }

        if (wallet.getBalance().compareTo(request.amount()) < 0) {
            throw new IllegalStateException("Insufficient wallet balance");
        }

        wallet.setBalance(wallet.getBalance().subtract(request.amount()));
        walletRepository.save(wallet);

        WalletTransaction walletTransaction = new WalletTransaction();
        walletTransaction.setWalletId(wallet.getId());
        walletTransaction.setType(WALLET_DEBIT);
        walletTransaction.setAmount(request.amount());
        walletTransaction.setTransactionId(transaction.getId());
        walletTransactionRepository.save(walletTransaction);
    }

    private void processPaymentMethod(CreatePaymentRequest request) {
        if (request.paymentMethodId() == null) {
            throw new IllegalArgumentException("Either walletId or paymentMethodId is required");
        }

        PaymentMethod paymentMethod = paymentMethodRepository.findByIdAndUserId(request.paymentMethodId(), request.userId())
                .orElseThrow(() -> new IllegalArgumentException("Payment method not found for user"));

        if (!"active".equalsIgnoreCase(paymentMethod.getStatus())) {
            throw new IllegalStateException("Payment method is not active");
        }
    }

    private void validateCreateRequest(CreatePaymentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request is required");
        }
        if (request.userId() == null) {
            throw new IllegalArgumentException("userId is required");
        }
        if (request.merchantId() == null) {
            throw new IllegalArgumentException("merchantId is required");
        }
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
        if (request.currency() == null || request.currency().isBlank()) {
            throw new IllegalArgumentException("currency is required");
        }
    }

    private String normalizeCurrency(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() != 3) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO code");
        }
        return normalized;
    }

    private void logEvent(Long transactionId, String event, String message) {
        TransactionLog log = new TransactionLog();
        log.setTransactionId(transactionId);
        log.setEvent(event);
        log.setMessage(message);
        transactionLogRepository.save(log);
    }

    private void publishEvent(String eventType, Transaction transaction, String message) {
        paymentEventPublisher.publish(new PaymentEvent(
                eventType,
                transaction.getId(),
                transaction.getUserId(),
                transaction.getMerchantId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus(),
                message,
                LocalDateTime.now()
        ));
    }
}
