package com.kimpay.payment.core.service;

import com.kimpay.payment.core.dto.CreatePaymentRequest;
import com.kimpay.payment.core.dto.PaymentResponse;
import com.kimpay.payment.core.dto.QRPaymentRequest;
import com.kimpay.payment.core.dto.RefundPaymentRequest;
import com.kimpay.payment.core.event.PaymentEvent;
import com.kimpay.payment.core.event.PaymentEventPublisher;
import com.kimpay.payment.core.exception.ResourceNotFoundException;
import com.kimpay.payment.core.ledger.LedgerLineRequest;
import com.kimpay.payment.core.ledger.LedgerPostingRequest;
import com.kimpay.payment.core.ledger.LedgerService;
import com.kimpay.payment.core.psp.*;
import com.kimpay.payment.core.repository.*;
import com.kimpay.payment.domain.entity.*;
import com.kimpay.payment.domain.entity.EntryDirection;
import com.kimpay.payment.domain.entity.EntryEventType;
import com.kimpay.payment.security.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
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
    private final EncryptionService encryptionService;
    private final QRService qrService;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final PspConnector pspConnector;
    private final TransactionFeeRepository transactionFeeRepository;
    private final LedgerService ledgerService;

    private static final String IDEMPOTENCY_PREFIX = "payment:idempotency:";
    private static final String MERCHANT_CACHE_PREFIX = "payment:merchant:exists:";
    private static final String LOCK_PREFIX = "payment:lock:";

    @Transactional
    public PaymentResponse createPayment(CreatePaymentRequest request) {
        validateCreateRequest(request);

        String idempotencyKey = request.idempotencyKey();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return processCreatePayment(request);
        }

        // Distributed Lock to prevent race conditions on the same idempotency key across different nodes
        RLock lock = redissonClient.getLock(LOCK_PREFIX + "idempotency:" + idempotencyKey);
        try {
            if (lock.tryLock(5, 10, TimeUnit.SECONDS)) {
                try {
                    String redisKey = IDEMPOTENCY_PREFIX + idempotencyKey;
                    if (Boolean.TRUE.equals(redisTemplate.hasKey(redisKey))) {
                        log.info("Duplicate request detected in Redis for idempotencyKey: {}", idempotencyKey);
                        return transactionRepository.findByIdempotencyKey(idempotencyKey)
                                .map(PaymentResponse::from)
                                .orElseThrow(() -> new IllegalStateException("Idempotency key in Redis but not in DB"));
                    }
                    return processCreatePayment(request);
                } finally {
                    lock.unlock();
                }
            } else {
                throw new IllegalStateException("Could not acquire lock for idempotency key: " + idempotencyKey);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for idempotency lock", e);
        }
    }

    private PaymentResponse processCreatePayment(CreatePaymentRequest request) {
        String idempotencyKey = request.idempotencyKey();
        // DB guard (fallback/final check)
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                redisTemplate.opsForValue().set(IDEMPOTENCY_PREFIX + idempotencyKey, "completed", 24, TimeUnit.HOURS);
                return PaymentResponse.from(existing.get());
            }
        }

        if (!userRepository.existsById(request.userId())) {
            throw new IllegalArgumentException("User not found: " + request.userId());
        }

        // High TPS Merchant check with Redis cache
        String merchantCacheKey = MERCHANT_CACHE_PREFIX + request.merchantId();
        if (Boolean.FALSE.equals(redisTemplate.hasKey(merchantCacheKey))) {
            if (!merchantRepository.existsById(request.merchantId())) {
                throw new IllegalArgumentException("Merchant not found: " + request.merchantId());
            }
            redisTemplate.opsForValue().set(merchantCacheKey, "active", 1, TimeUnit.HOURS);
        }

        Transaction transaction = new Transaction();
        transaction.setUserId(request.userId());
        transaction.setMerchantId(request.merchantId());
        transaction.setPaymentMethodId(request.paymentMethodId());
        transaction.setWalletId(request.walletId());
        transaction.setAmount(request.amount());
        transaction.setCurrency(normalizeCurrency(request.currency()));
        transaction.setIdempotencyKey(request.idempotencyKey());
        transaction.authorize();

        transaction = transactionRepository.save(transaction);
        logEvent(transaction.getId(), "AUTHORIZED", "Transaction authorized");
        publishEvent("AUTHORIZED", transaction, "Transaction authorized");

        // If capture is false, stop here (Authorize only)
        if (request.capture() != null && !request.capture()) {
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                redisTemplate.opsForValue().set(IDEMPOTENCY_PREFIX + idempotencyKey, "completed", 24, TimeUnit.HOURS);
            }
            return PaymentResponse.from(transaction);
        }

        try {
            PaymentResponse response = completeCapture(transaction, request.walletId(), request.paymentMethodId());
            // Save to Redis after successful completion
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                redisTemplate.opsForValue().set(IDEMPOTENCY_PREFIX + idempotencyKey, "completed", 24, TimeUnit.HOURS);
            }
            return response;
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
                .orElseThrow(ResourceNotFoundException::new);

        if (!PaymentStatus.AUTHORIZED.name().equals(transaction.getStatus())) {
            throw new IllegalStateException("Only AUTHORIZED transactions can be captured");
        }

        if (transaction.getWalletId() != null) {
            // Wallet-backed: perform the real debit now, using the persisted wallet.
            processWalletDebit(transaction.getWalletId(), transaction.getUserId(),
                    transaction.getAmount(), transaction.getCurrency(), transaction.getId());
        } else {
            // Card-backed: capture the previously-authorized PSP reference. Fail loud rather
            // than silently flip status if no reference was ever stored.
            if (transaction.getPspReference() == null) {
                throw new IllegalStateException("Card transaction is missing a PSP reference");
            }
            PspResult result = pspConnector.capture(transaction.getPspReference(), transaction.getAmount());
            if (!result.isSuccess()) {
                throw new IllegalStateException("PSP capture failed");
            }
        }

        transaction.capture();
        transaction = transactionRepository.save(transaction);
        ledgerService.post(buildCapturePosting(transaction));
        logEvent(transactionId, "CAPTURED", "Capture successful");
        publishEvent("CAPTURED", transaction, "Capture successful");
        return PaymentResponse.from(transaction);
    }

    @Transactional
    public PaymentResponse voidPayment(Long transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException());

        if (transaction.getWalletId() == null) {
            // Card-backed: void at the PSP. Fail loud if no reference was stored.
            if (transaction.getPspReference() == null) {
                throw new IllegalStateException("Card transaction is missing a PSP reference");
            }
            PspResult result = pspConnector.voidAuthorization(transaction.getPspReference());
            if (!result.isSuccess()) {
                throw new IllegalStateException("PSP void failed");
            }
        }

        transaction.voidTransaction();
        transaction = transactionRepository.save(transaction);
        logEvent(transactionId, "VOIDED", "Transaction voided");
        publishEvent("VOIDED", transaction, "Transaction voided");
        return PaymentResponse.from(transaction);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getTransactionsByUser(Long userId) {
        return PaymentResponse.fromList(transactionRepository.findByUserIdOrderByCreatedAtDesc(userId));
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getTransactionsByMerchant(Long merchantId) {
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
            processPaymentMethod(methodId, transaction.getUserId()); // validates method is active
            PspResult auth = pspConnector.authorize(new PspAuthorizeRequest(
                    transaction.getId(), methodId, transaction.getAmount(), transaction.getCurrency(), true));
            transaction.setPspReference(auth.pspReference());
            if (!auth.isSuccess()) {
                throw new IllegalStateException("Payment authorization declined");
            }
        }

        transaction.capture();
        transaction = transactionRepository.save(transaction);
        ledgerService.post(buildCapturePosting(transaction));
        logEvent(transaction.getId(), "CAPTURED", "Transaction captured");
        publishEvent("CAPTURED", transaction, "Transaction captured");
        return PaymentResponse.from(transaction);
    }

    /**
     * Process a scanned QR payment.
     *
     * @param request               the scan request (payer wallet/user + encrypted QR payload)
     * @param authenticatedMerchantId the merchant making the call; MUST own the QR being collected.
     *                                A mismatch yields {@link ResourceNotFoundException} (404) so a
     *                                merchant cannot forge payments against another merchant's QR.
     */
    @Transactional
    public PaymentResponse processQRPayment(QRPaymentRequest request, Long authenticatedMerchantId) {
        Long merchantId;
        BigDecimal amount;
        String currency;
        try {
            // Decrypt the QR data using standard AES-GCM (no mocking)
            String decryptedData = encryptionService.decrypt(request.qrData());

            // Expected format: mId:123|amt:10.00|cur:USD|ts:1710101010
            String merchantIdStr = null;
            amount = null;
            currency = null;

            String[] pairs = decryptedData.split("\\|");
            for (String pair : pairs) {
                String[] kv = pair.split(":");
                if (kv.length != 2) continue;
                switch (kv[0]) {
                    case "mId" -> merchantIdStr = kv[1];
                    case "amt" -> amount = new BigDecimal(kv[1]);
                    case "cur" -> currency = kv[1];
                }
            }

            if (merchantIdStr == null || amount == null || currency == null) {
                throw new IllegalArgumentException("Invalid QR payload");
            }
            merchantId = Long.parseLong(merchantIdStr);
        } catch (Exception e) {
            log.error("QR processing failed", e);
            throw new IllegalArgumentException("QR Payment Failed: " + e.getMessage());
        }

        // Object-level authorization: a merchant may only collect against its OWN QR code.
        if (authenticatedMerchantId == null || !authenticatedMerchantId.equals(merchantId)) {
            throw new ResourceNotFoundException();
        }

        CreatePaymentRequest createRequest = new CreatePaymentRequest(
            request.userId(),
            merchantId,
            request.paymentMethodId(),
            request.walletId(),
            amount,
            currency,
            true, // Auto-capture for retail QR scans
            request.idempotencyKey()
        );

        return createPayment(createRequest);
    }

    public String generateMerchantQRImage(Long merchantId, BigDecimal amount, String currency) {
        // Create a structured payload and encrypt it for security
        String payload = String.format("mId:%d|amt:%.2f|cur:%s|ts:%d", 
                merchantId, amount, currency, System.currentTimeMillis() / 1000);
        
        String encryptedPayload = encryptionService.encrypt(payload);
        
        // Generate actual QR image Base64 (no mocking)
        return qrService.generateQRCodeBase64(encryptedPayload, 300, 300);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(Long transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException());
        return PaymentResponse.from(transaction);
    }

    @Transactional
    public PaymentResponse refundPayment(Long transactionId, RefundPaymentRequest request) {
        if (request == null || request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Refund amount must be greater than zero");
        }

        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException());

        if (!PaymentStatus.CAPTURED.name().equals(transaction.getStatus()) &&
                !PaymentStatus.PARTIALLY_REFUNDED.name().equals(transaction.getStatus())) {
            throw new IllegalStateException("Only CAPTURED or PARTIALLY_REFUNDED transactions can be refunded");
        }

        // Calculate cumulative refunded total to support partial refunds
        List<Refund> existingRefunds = refundRepository.findAllByTransactionId(transactionId);
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

        if (transaction.getWalletId() == null) {
            // Card-backed: refund at the PSP. Fail loud if no reference was stored.
            if (transaction.getPspReference() == null) {
                throw new IllegalStateException("Card transaction is missing a PSP reference");
            }
            PspResult result = pspConnector.refund(transaction.getPspReference(), request.amount());
            if (!result.isSuccess()) {
                throw new IllegalStateException("PSP refund failed");
            }
        }

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

        ledgerService.post(buildRefundPosting(transaction, request.amount()));

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
        // Distributed Lock at User/Wallet level to ensure cross-node consistency
        RLock lock = redissonClient.getLock(LOCK_PREFIX + "wallet:" + walletId);
        try {
            if (lock.tryLock(5, 10, TimeUnit.SECONDS)) {
                try {
                    // Still use Pessimistic Lock in DB as secondary safety
                    Wallet wallet = walletRepository.findWithLockByIdAndUserId(walletId, userId)
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
                } finally {
                    lock.unlock();
                }
            } else {
                throw new IllegalStateException("Could not acquire lock for wallet: " + walletId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for wallet lock", e);
        }
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

    private LedgerPostingRequest buildCapturePosting(Transaction transaction) {
        String payerCode = transaction.getWalletId() != null
                ? "WALLET:" + transaction.getWalletId()
                : "SYS:PSP_CLEARING";
        String merchantCode = "MERCHANT:" + transaction.getMerchantId();
        String currency = transaction.getCurrency();
        BigDecimal amount = transaction.getAmount();

        BigDecimal fee = transactionFeeRepository.findAllByTransactionId(transaction.getId())
                .stream().map(com.kimpay.payment.domain.entity.TransactionFee::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<LedgerLineRequest> lines = new ArrayList<>();
        lines.add(new LedgerLineRequest(payerCode, currency, EntryDirection.DEBIT, amount));
        if (fee.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new LedgerLineRequest(merchantCode, currency, EntryDirection.CREDIT, amount.subtract(fee)));
            lines.add(new LedgerLineRequest("SYS:FEE_REVENUE", currency, EntryDirection.CREDIT, fee));
        } else {
            lines.add(new LedgerLineRequest(merchantCode, currency, EntryDirection.CREDIT, amount));
        }
        return new LedgerPostingRequest(transaction.getId(), EntryEventType.CAPTURE,
                "Capture txn " + transaction.getId(), lines);
    }

    private LedgerPostingRequest buildRefundPosting(Transaction transaction, BigDecimal refundAmount) {
        String payerCode = transaction.getWalletId() != null
                ? "WALLET:" + transaction.getWalletId()
                : "SYS:PSP_CLEARING";
        String merchantCode = "MERCHANT:" + transaction.getMerchantId();
        String currency = transaction.getCurrency();
        return new LedgerPostingRequest(transaction.getId(), EntryEventType.REFUND,
                "Refund txn " + transaction.getId(),
                List.of(
                        new LedgerLineRequest(merchantCode, currency, EntryDirection.DEBIT, refundAmount),
                        new LedgerLineRequest(payerCode, currency, EntryDirection.CREDIT, refundAmount)
                ));
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
