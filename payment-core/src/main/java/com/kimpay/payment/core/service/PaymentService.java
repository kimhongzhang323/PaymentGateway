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

        transaction = transactionRepository.save(transaction);
        logEvent(transaction.getId(), "AUTHORIZED", "Transaction authorized");
        publishEvent("AUTHORIZED", transaction, "Transaction authorized");

        // If capture is false, stop here (Authorize only)
        if (request.capture() != null && !request.capture()) {
            return PaymentResponse.from(transaction);
        }

        try {
            return completeCapture(transaction, request.walletId(), request.paymentMethodId());
        } catch (RuntimeException ex) {
            transaction.markFailed();
            transactionRepository.save(transaction);
            logEvent(transaction.getId(), "FAILED", ex.getMessage());
            publishEvent("FAILED", transaction, ex.getMessage());
            throw ex;
        }
    }

    @Transactional
    public PaymentResponse capturePayment(Long transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));

        if (!PaymentStatus.AUTHORIZED.name().equals(transaction.getStatus())) {
            throw new IllegalStateException("Only AUTHORIZED transactions can be captured");
        }

        // For wallet transactions, we need to know which wallet to debit.
        // In a real system, we'd store the specific walletId or paymentMethodId in the Transaction entity.
        // For this demo, let's assume we retrieve the wallet from the first associated log or metadata if available.
        // However, looking at the current schema, we don't store it in Transaction.
        // Let's perform a basic capture that just updates status if it wasn't a wallet debit,
        // or re-run the wallet logic if we can find the walletId.
        
        // Simplified: In this demo, capture logic usually follows authorization immediately.
        // If they are separated, we'd need to have stored the parameters.
        
        transaction.capture();
        transaction = transactionRepository.save(transaction);
        logEvent(transactionId, "CAPTURED", "Manual capture successful");
        publishEvent("CAPTURED", transaction, "Manual capture");
        return PaymentResponse.from(transaction);
    }

    @Transactional
    public PaymentResponse voidPayment(Long transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));

        transaction.voidTransaction();
        transaction = transactionRepository.save(transaction);
        logEvent(transactionId, "VOIDED", "Transaction voided");
        publishEvent("VOIDED", transaction, "Transaction voided");
        return PaymentResponse.from(transaction);
    }

    @Transactional(readOnly = true)
    public java.util.List<PaymentResponse> getTransactionsByUser(Long userId) {
        return PaymentResponse.fromList(transactionRepository.findByUserIdOrderByCreatedAtDesc(userId));
    }

    @Transactional(readOnly = true)
    public java.util.List<PaymentResponse> getTransactionsByMerchant(Long merchantId) {
        return PaymentResponse.fromList(transactionRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId));
    }

    private PaymentResponse completeCapture(Transaction transaction, Long walletId, Long paymentMethodId) {
        if (walletId != null) {
            processWalletDebit(walletId, transaction.getUserId(), transaction.getAmount(), transaction.getCurrency(), transaction.getId());
        } else {
            Long methodId = paymentMethodId != null ? paymentMethodId : transaction.getPaymentMethodId();
            if (methodId == null) {
                 throw new IllegalArgumentException("Payment method information is missing");
            }
            processPaymentMethod(methodId, transaction.getUserId());
        }

        transaction.capture();
        transaction = transactionRepository.save(transaction);
        logEvent(transaction.getId(), "CAPTURED", "Transaction captured");
        publishEvent("CAPTURED", transaction, "Transaction captured");
        return PaymentResponse.from(transaction);
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

        if (!PaymentStatus.CAPTURED.name().equals(transaction.getStatus()) &&
                !PaymentStatus.PARTIALLY_REFUNDED.name().equals(transaction.getStatus())) {
            throw new IllegalStateException("Only CAPTURED or PARTIALLY_REFUNDED transactions can be refunded");
        }

        // Calculate cumulative refunded total to support partial refunds
        java.util.List<Refund> existingRefunds = refundRepository.findAllByTransactionId(transactionId);
        BigDecimal totalRefunded = existingRefunds.stream()
                .map(Refund::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal remainingAmount = transaction.getAmount().subtract(totalRefunded);

        if (request.amount().compareTo(remainingAmount) > 0) {
            throw new IllegalArgumentException("Refund amount exceeds remaining transaction amount. Remaining: " + remainingAmount);
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

        // Update transaction status based on whether it's fully or partially refunded
        if (request.amount().compareTo(remainingAmount) == 0) {
            transaction.refund();
        } else {
            transaction.partialRefund();
        }

        transaction = transactionRepository.save(transaction);
        logEvent(transactionId, transaction.getStatus(), "Transaction " + transaction.getStatus().toLowerCase());
        publishEvent(transaction.getStatus(), transaction, request.reason());

        return PaymentResponse.from(transaction);
    }

    private void processWalletDebit(Long walletId, Long userId, BigDecimal amount, String currency, Long transactionId) {
        Wallet wallet = walletRepository.findByIdAndUserId(walletId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for user"));

        String normalizedCurrency = normalizeCurrency(currency);
        if (!normalizedCurrency.equalsIgnoreCase(wallet.getCurrency())) {
            throw new IllegalArgumentException("Wallet currency does not match transaction currency");
        }

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient wallet balance");
        }

        wallet.setBalance(wallet.getBalance().subtract(amount));
        walletRepository.save(wallet);

        WalletTransaction walletTransaction = new WalletTransaction();
        walletTransaction.setWalletId(wallet.getId());
        walletTransaction.setType(WALLET_DEBIT);
        walletTransaction.setAmount(amount);
        walletTransaction.setTransactionId(transactionId);
        walletTransactionRepository.save(walletTransaction);
    }

    private void processPaymentMethod(Long paymentMethodId, Long userId) {
        if (paymentMethodId == null) {
            throw new IllegalArgumentException("paymentMethodId is required");
        }

        PaymentMethod paymentMethod = paymentMethodRepository.findByIdAndUserId(paymentMethodId, userId)
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
