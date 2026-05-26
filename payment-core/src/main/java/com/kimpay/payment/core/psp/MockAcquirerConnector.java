package com.kimpay.payment.core.psp;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Deterministic, offline PSP used for local development and CI. Approves by default; declines
 * any amount whose fractional minor units equal .01 so decline paths are testable without a
 * real acquirer. Never contacts the network. Logs no PAN/secret material.
 */
@Slf4j
public class MockAcquirerConnector implements PspConnector {

    private static final BigDecimal DECLINE_TRIGGER = new BigDecimal("0.01");

    @Override
    public PspResult authorize(PspAuthorizeRequest request) {
        if (isDeclineAmount(request.amount())) {
            log.info("[mock-psp] declining txn {} (magic decline amount)", request.transactionId());
            return PspResult.declined(newReference(), "Card declined (mock)");
        }
        PspStatus status = request.capture() ? PspStatus.CAPTURED : PspStatus.AUTHORIZED;
        return PspResult.ok(status, newReference());
    }

    @Override
    public PspResult capture(String pspReference, BigDecimal amount) {
        return PspResult.ok(PspStatus.CAPTURED, pspReference);
    }

    @Override
    public PspResult voidAuthorization(String pspReference) {
        return PspResult.ok(PspStatus.VOIDED, pspReference);
    }

    @Override
    public PspResult refund(String pspReference, BigDecimal amount) {
        return PspResult.ok(PspStatus.REFUNDED, pspReference);
    }

    private boolean isDeclineAmount(BigDecimal amount) {
        if (amount == null) {
            return false;
        }
        BigDecimal fractional = amount.remainder(BigDecimal.ONE).abs().setScale(2, java.math.RoundingMode.HALF_UP);
        return fractional.compareTo(DECLINE_TRIGGER) == 0;
    }

    private String newReference() {
        return "mock_" + UUID.randomUUID().toString().replace("-", "");
    }
}
