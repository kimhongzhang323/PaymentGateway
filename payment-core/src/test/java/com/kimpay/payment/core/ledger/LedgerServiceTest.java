package com.kimpay.payment.core.ledger;

import com.kimpay.payment.core.repository.JournalEntryRepository;
import com.kimpay.payment.core.repository.JournalLineRepository;
import com.kimpay.payment.core.repository.LedgerAccountRepository;
import com.kimpay.payment.domain.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LedgerServiceTest {

    private LedgerAccountRepository accountRepo;
    private JournalEntryRepository entryRepo;
    private JournalLineRepository lineRepo;
    private LedgerService service;

    @BeforeEach
    void setUp() {
        accountRepo = mock(LedgerAccountRepository.class);
        entryRepo = mock(JournalEntryRepository.class);
        lineRepo = mock(JournalLineRepository.class);
        service = new LedgerService(accountRepo, entryRepo, lineRepo);

        LedgerAccount wallet = makeAccount(1L, "WALLET:7", "USD", AccountClassification.LIABILITY, BigDecimal.ZERO);
        LedgerAccount merchant = makeAccount(2L, "MERCHANT:2", "USD", AccountClassification.LIABILITY, BigDecimal.ZERO);
        when(accountRepo.findByCodeAndCurrency("WALLET:7", "USD")).thenReturn(Optional.of(wallet));
        when(accountRepo.findByCodeAndCurrency("MERCHANT:2", "USD")).thenReturn(Optional.of(merchant));
        when(accountRepo.findWithLockById(1L)).thenReturn(Optional.of(wallet));
        when(accountRepo.findWithLockById(2L)).thenReturn(Optional.of(merchant));
        when(accountRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        JournalEntry savedEntry = new JournalEntry();
        savedEntry.setId(99L);
        savedEntry.setTransactionId(42L);
        savedEntry.setEventType(EntryEventType.CAPTURE);
        when(entryRepo.save(any())).thenReturn(savedEntry);
        when(lineRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void rejectsFewerThanTwoLines() {
        LedgerPostingRequest req = new LedgerPostingRequest(42L, EntryEventType.CAPTURE, "test",
                List.of(new LedgerLineRequest("WALLET:7", "USD", EntryDirection.DEBIT, new BigDecimal("10.00"))));
        assertThatThrownBy(() -> service.post(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 lines");
    }

    @Test
    void rejectsUnbalancedEntry() {
        LedgerPostingRequest req = new LedgerPostingRequest(42L, EntryEventType.CAPTURE, "test", List.of(
                new LedgerLineRequest("WALLET:7", "USD", EntryDirection.DEBIT, new BigDecimal("10.00")),
                new LedgerLineRequest("MERCHANT:2", "USD", EntryDirection.CREDIT, new BigDecimal("9.00"))
        ));
        assertThatThrownBy(() -> service.post(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("balanced");
    }

    @Test
    void rejectsZeroAmount() {
        LedgerPostingRequest req = new LedgerPostingRequest(42L, EntryEventType.CAPTURE, "test", List.of(
                new LedgerLineRequest("WALLET:7", "USD", EntryDirection.DEBIT, BigDecimal.ZERO),
                new LedgerLineRequest("MERCHANT:2", "USD", EntryDirection.CREDIT, BigDecimal.ZERO)
        ));
        assertThatThrownBy(() -> service.post(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void rejectsMixedCurrency() {
        LedgerPostingRequest req = new LedgerPostingRequest(42L, EntryEventType.CAPTURE, "test", List.of(
                new LedgerLineRequest("WALLET:7", "USD", EntryDirection.DEBIT, new BigDecimal("10.00")),
                new LedgerLineRequest("MERCHANT:2", "EUR", EntryDirection.CREDIT, new BigDecimal("10.00"))
        ));
        assertThatThrownBy(() -> service.post(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void debitOnLiabilityDecreasesBalance() {
        LedgerAccount wallet = makeAccount(1L, "WALLET:7", "USD", AccountClassification.LIABILITY, new BigDecimal("50.00"));
        LedgerAccount merchant = makeAccount(2L, "MERCHANT:2", "USD", AccountClassification.LIABILITY, BigDecimal.ZERO);
        when(accountRepo.findByCodeAndCurrency("WALLET:7", "USD")).thenReturn(Optional.of(wallet));
        when(accountRepo.findWithLockById(1L)).thenReturn(Optional.of(wallet));
        when(accountRepo.findByCodeAndCurrency("MERCHANT:2", "USD")).thenReturn(Optional.of(merchant));
        when(accountRepo.findWithLockById(2L)).thenReturn(Optional.of(merchant));

        service.post(new LedgerPostingRequest(42L, EntryEventType.CAPTURE, "test", List.of(
                new LedgerLineRequest("WALLET:7", "USD", EntryDirection.DEBIT, new BigDecimal("10.00")),
                new LedgerLineRequest("MERCHANT:2", "USD", EntryDirection.CREDIT, new BigDecimal("10.00"))
        )));

        assertThat(wallet.getBalance()).isEqualByComparingTo("40.00");
    }

    @Test
    void creditOnLiabilityIncreasesBalance() {
        LedgerAccount wallet = makeAccount(1L, "WALLET:7", "USD", AccountClassification.LIABILITY, new BigDecimal("50.00"));
        LedgerAccount merchant = makeAccount(2L, "MERCHANT:2", "USD", AccountClassification.LIABILITY, BigDecimal.ZERO);
        when(accountRepo.findByCodeAndCurrency("WALLET:7", "USD")).thenReturn(Optional.of(wallet));
        when(accountRepo.findWithLockById(1L)).thenReturn(Optional.of(wallet));
        when(accountRepo.findByCodeAndCurrency("MERCHANT:2", "USD")).thenReturn(Optional.of(merchant));
        when(accountRepo.findWithLockById(2L)).thenReturn(Optional.of(merchant));

        service.post(new LedgerPostingRequest(42L, EntryEventType.CAPTURE, "test", List.of(
                new LedgerLineRequest("WALLET:7", "USD", EntryDirection.DEBIT, new BigDecimal("10.00")),
                new LedgerLineRequest("MERCHANT:2", "USD", EntryDirection.CREDIT, new BigDecimal("10.00"))
        )));

        assertThat(merchant.getBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    void debitOnAssetIncreasesBalance() {
        LedgerAccount psp = makeAccount(3L, "SYS:PSP_CLEARING", "USD", AccountClassification.ASSET, BigDecimal.ZERO);
        LedgerAccount merchant = makeAccount(2L, "MERCHANT:2", "USD", AccountClassification.LIABILITY, BigDecimal.ZERO);
        when(accountRepo.findByCodeAndCurrency("SYS:PSP_CLEARING", "USD")).thenReturn(Optional.of(psp));
        when(accountRepo.findByCodeAndCurrency("MERCHANT:2", "USD")).thenReturn(Optional.of(merchant));
        when(accountRepo.findWithLockById(2L)).thenReturn(Optional.of(merchant));
        when(accountRepo.findWithLockById(3L)).thenReturn(Optional.of(psp));

        service.post(new LedgerPostingRequest(42L, EntryEventType.CAPTURE, "test", List.of(
                new LedgerLineRequest("SYS:PSP_CLEARING", "USD", EntryDirection.DEBIT, new BigDecimal("10.00")),
                new LedgerLineRequest("MERCHANT:2", "USD", EntryDirection.CREDIT, new BigDecimal("10.00"))
        )));

        assertThat(psp.getBalance()).isEqualByComparingTo("10.00");
    }

    private LedgerAccount makeAccount(Long id, String code, String currency,
                                       AccountClassification cls, BigDecimal balance) {
        LedgerAccount acc = new LedgerAccount();
        acc.setId(id);
        acc.setCode(code);
        acc.setCurrency(currency);
        acc.setClassification(cls);
        acc.setBalance(balance);
        return acc;
    }
}
