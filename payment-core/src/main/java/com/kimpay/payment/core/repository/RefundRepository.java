package com.kimpay.payment.core.repository;

import com.kimpay.payment.domain.entity.Refund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RefundRepository extends JpaRepository<Refund, Long> {
    List<Refund> findAllByTransactionId(Long transactionId);
}
