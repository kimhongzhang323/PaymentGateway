package com.kimpay.payment.core.repository;

import com.kimpay.payment.domain.entity.EntryDirection;
import com.kimpay.payment.domain.entity.JournalLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.util.List;

public interface JournalLineRepository extends JpaRepository<JournalLine, Long> {

    List<JournalLine> findAllByJournalEntryId(Long journalEntryId);

    @Query("SELECT SUM(l.amount) FROM JournalLine l WHERE l.direction = :direction")
    BigDecimal sumByDirection(@Param("direction") EntryDirection direction);
}
