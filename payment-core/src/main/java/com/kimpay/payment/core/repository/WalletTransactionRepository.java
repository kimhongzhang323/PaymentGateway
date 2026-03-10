package com.kimpay.payment.core.repository;

import com.kimpay.payment.domain.entity.WalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {
    Optional<WalletTransaction> findFirstByTransactionIdAndTypeOrderByCreatedAtDesc(Long transactionId, String type);
}
