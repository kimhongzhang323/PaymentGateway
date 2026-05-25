package com.kimpay.payment.core.repository;

import com.kimpay.payment.domain.entity.ApiCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApiCredentialRepository extends JpaRepository<ApiCredential, Long> {
    Optional<ApiCredential> findByKeyId(String keyId);
}
