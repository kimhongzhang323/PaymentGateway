package com.kimpay.payment.core.repository;

import com.kimpay.payment.domain.entity.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {
    List<JournalEntry> findAllByTransactionId(Long transactionId);
}
