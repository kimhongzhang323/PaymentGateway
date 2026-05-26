package com.kimpay.payment.core.ledger;

import com.kimpay.payment.domain.entity.EntryDirection;
import java.math.BigDecimal;

/**
 * One debit or credit line in a posting request.
 *
 * @param accountCode  e.g. "WALLET:7", "MERCHANT:2", "SYS:FEE_REVENUE"
 * @param currency     ISO 4217 uppercase
 * @param direction    DEBIT or CREDIT
 * @param amount       must be > 0
 */
public record LedgerLineRequest(
        String accountCode,
        String currency,
        EntryDirection direction,
        BigDecimal amount
) {}
