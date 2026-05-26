package com.kimpay.payment.core.ledger;

import com.kimpay.payment.core.repository.JournalEntryRepository;
import com.kimpay.payment.core.repository.JournalLineRepository;
import com.kimpay.payment.core.repository.LedgerAccountRepository;
import com.kimpay.payment.domain.entity.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final LedgerAccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalLineRepository journalLineRepository;

    /**
     * Post a balanced journal entry. Must be called inside an existing @Transactional
     * so the ledger write and the surrounding money-movement share one DB transaction.
     */
    @Transactional
    public void post(LedgerPostingRequest request) {
        validate(request);

        // Resolve all needed accounts (find or create lazily for owner accounts)
        Map<String, LedgerAccount> accountsByKey = new LinkedHashMap<>();
        for (LedgerLineRequest line : request.lines()) {
            String key = line.accountCode() + ":" + line.currency();
            if (!accountsByKey.containsKey(key)) {
                accountsByKey.put(key, findOrCreate(line.accountCode(), line.currency()));
            }
        }

        // Lock all accounts ordered by id to prevent deadlock
        List<Long> sortedIds = accountsByKey.values().stream()
                .map(LedgerAccount::getId)
                .sorted()
                .collect(Collectors.toList());
        Map<Long, LedgerAccount> lockedById = new LinkedHashMap<>();
        for (Long id : sortedIds) {
            lockedById.put(id, accountRepository.findWithLockById(id)
                    .orElseThrow(() -> new IllegalStateException("Ledger account disappeared: " + id)));
        }
        // Remap by code:currency using locked instances
        Map<String, LedgerAccount> locked = new LinkedHashMap<>();
        for (Map.Entry<String, LedgerAccount> e : accountsByKey.entrySet()) {
            locked.put(e.getKey(), lockedById.get(e.getValue().getId()));
        }

        // Persist entry header
        JournalEntry entry = new JournalEntry();
        entry.setTransactionId(request.transactionId());
        entry.setEventType(request.eventType());
        entry.setDescription(request.description());
        entry = journalEntryRepository.save(entry);

        // Persist lines and apply balance deltas
        for (LedgerLineRequest lineReq : request.lines()) {
            String key = lineReq.accountCode() + ":" + lineReq.currency();
            LedgerAccount account = locked.get(key);

            JournalLine line = new JournalLine();
            line.setJournalEntryId(entry.getId());
            line.setAccountId(account.getId());
            line.setDirection(lineReq.direction());
            line.setAmount(lineReq.amount());
            line.setCurrency(lineReq.currency());
            journalLineRepository.save(line);

            applyDelta(account, lineReq.direction(), lineReq.amount());
            accountRepository.save(account);
        }

        log.debug("[ledger] posted {} entry for txn {}: {} lines",
                request.eventType(), request.transactionId(), request.lines().size());
    }

    /**
     * Returns true if sum of all DEBIT line amounts equals sum of all CREDIT line amounts.
     */
    @Transactional(readOnly = true)
    public boolean trialBalance() {
        BigDecimal debits = journalLineRepository.sumByDirection(EntryDirection.DEBIT);
        BigDecimal credits = journalLineRepository.sumByDirection(EntryDirection.CREDIT);
        if (debits == null) debits = BigDecimal.ZERO;
        if (credits == null) credits = BigDecimal.ZERO;
        return debits.compareTo(credits) == 0;
    }

    private void validate(LedgerPostingRequest request) {
        List<LedgerLineRequest> lines = request.lines();
        if (lines == null || lines.size() < 2) {
            throw new IllegalArgumentException("Journal entry requires at least 2 lines");
        }
        long distinctCurrencies = lines.stream().map(LedgerLineRequest::currency).distinct().count();
        if (distinctCurrencies != 1) {
            throw new IllegalArgumentException("All lines in a journal entry must share a single currency");
        }
        for (LedgerLineRequest line : lines) {
            if (line.amount() == null || line.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Journal line amount must be > 0");
            }
        }
        BigDecimal debits = lines.stream()
                .filter(l -> l.direction() == EntryDirection.DEBIT)
                .map(LedgerLineRequest::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = lines.stream()
                .filter(l -> l.direction() == EntryDirection.CREDIT)
                .map(LedgerLineRequest::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (debits.compareTo(credits) != 0) {
            throw new IllegalArgumentException(
                    "Journal entry is not balanced: debits=" + debits + " credits=" + credits);
        }
    }

    private LedgerAccount findOrCreate(String code, String currency) {
        return accountRepository.findByCodeAndCurrency(code, currency)
                .orElseGet(() -> createAccount(code, currency));
    }

    private LedgerAccount createAccount(String code, String currency) {
        LedgerAccount acc = new LedgerAccount();
        acc.setCode(code);
        acc.setCurrency(currency);
        acc.setBalance(BigDecimal.ZERO);
        if (code.startsWith("WALLET:")) {
            acc.setOwnerType(AccountOwnerType.USER_WALLET);
            acc.setOwnerId(Long.parseLong(code.substring(7)));
            acc.setClassification(AccountClassification.LIABILITY);
        } else if (code.startsWith("MERCHANT:")) {
            acc.setOwnerType(AccountOwnerType.MERCHANT);
            acc.setOwnerId(Long.parseLong(code.substring(9)));
            acc.setClassification(AccountClassification.LIABILITY);
        } else {
            acc.setOwnerType(AccountOwnerType.SYSTEM);
            acc.setClassification(systemClassification(code));
        }
        return accountRepository.save(acc);
    }

    private AccountClassification systemClassification(String code) {
        return switch (code) {
            case "SYS:PSP_CLEARING", "SYS:GATEWAY_CASH" -> AccountClassification.ASSET;
            case "SYS:FEE_REVENUE" -> AccountClassification.REVENUE;
            default -> throw new IllegalArgumentException("Unknown system account: " + code);
        };
    }

    /**
     * Balance delta = (DEBIT?+1:-1) * (ASSET?+1:-1) * amount.
     * ASSET is debit-normal: DR increases, CR decreases.
     * LIABILITY/REVENUE are credit-normal: CR increases, DR decreases.
     */
    private void applyDelta(LedgerAccount account, EntryDirection direction, BigDecimal amount) {
        int debitSign = direction == EntryDirection.DEBIT ? 1 : -1;
        int classSign = account.getClassification() == AccountClassification.ASSET ? 1 : -1;
        BigDecimal delta = amount.multiply(BigDecimal.valueOf((long) debitSign * classSign));
        account.setBalance(account.getBalance().add(delta));
    }
}
