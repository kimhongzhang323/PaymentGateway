package com.kimpay.payment.core.repository;

import com.kimpay.payment.domain.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<Transaction> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
}
