package com.kimpay.payment.core.repository;

import com.kimpay.payment.domain.entity.TransactionFee;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TransactionFeeRepository extends JpaRepository<TransactionFee, Long> {
    List<TransactionFee> findAllByTransactionId(Long transactionId);
}
