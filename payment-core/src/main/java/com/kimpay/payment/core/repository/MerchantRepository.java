package com.kimpay.payment.core.repository;

import com.kimpay.payment.domain.entity.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantRepository extends JpaRepository<Merchant, Long> {
}
