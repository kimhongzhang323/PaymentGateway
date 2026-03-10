package com.kimpay.payment.core.repository;

import com.kimpay.payment.domain.entity.TransactionLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionLogRepository extends JpaRepository<TransactionLog, Long> {
}
