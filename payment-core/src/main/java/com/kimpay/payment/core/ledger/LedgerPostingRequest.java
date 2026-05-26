package com.kimpay.payment.core.ledger;

import com.kimpay.payment.domain.entity.EntryEventType;
import java.util.List;

/**
 * A complete, atomic posting request. All lines must balance (Σdebits == Σcredits),
 * share a single currency, and contain >= 2 lines with amounts > 0.
 */
public record LedgerPostingRequest(
        Long transactionId,
        EntryEventType eventType,
        String description,
        List<LedgerLineRequest> lines
) {}
