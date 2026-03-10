package com.kimpay.payment.core.repository;

import com.kimpay.payment.domain.entity.PaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, Long> {
    Optional<PaymentMethod> findByIdAndUserId(Long id, Long userId);
}
