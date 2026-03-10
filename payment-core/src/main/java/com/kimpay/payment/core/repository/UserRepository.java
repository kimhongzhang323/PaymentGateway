package com.kimpay.payment.core.repository;

import com.kimpay.payment.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}
