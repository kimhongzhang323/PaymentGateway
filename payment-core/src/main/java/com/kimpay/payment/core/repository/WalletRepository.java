package com.kimpay.payment.core.repository;

import com.kimpay.payment.domain.entity.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {
    Optional<Wallet> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select w from Wallet w where w.id = ?1 and w.userId = ?2")
    Optional<Wallet> findWithLockByIdAndUserId(Long id, Long userId);
}
