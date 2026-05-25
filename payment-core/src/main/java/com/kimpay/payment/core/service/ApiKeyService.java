package com.kimpay.payment.core.service;

import com.kimpay.payment.core.repository.ApiCredentialRepository;
import com.kimpay.payment.domain.entity.ApiCredential;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * =============================================================================
 * ApiKeyService.java
 * -----------------------------------------------------------------------------
 * Project      : payment
 * Module       : com.kimpay.payment.core.service
 * Author       : kimho
 * Created On   : 25/05/2026
 * -----------------------------------------------------------------------------
 * Description  : ApiKeyService - Issues and verifies merchant API keys.
 *                Secrets are returned in plaintext only at issuance; only the
 *                BCrypt hash is persisted. Secrets are never logged.
 * -----------------------------------------------------------------------------
 * COPYRIGHT NOTICE
 * -----------------------------------------------------------------------------
 * © 2025 Kimpay Technologies. All Rights Reserved.
 * Unauthorized copying, modification, distribution, or disclosure of this
 * file, via any medium, is strictly prohibited. This file contains proprietary
 * and confidential information of Kimpay Technologies.
 * -----------------------------------------------------------------------------
 * SECURITY NOTICE
 * -----------------------------------------------------------------------------
 * This class may process sensitive financial or personal data. Ensure all
 * logs, outputs, and interactions comply with internal data-handling policies
 * and regulatory requirements (e.g., PCI DSS, GDPR).
 * =============================================================================
 */
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final ApiCredentialRepository repository;
    private final PasswordEncoder passwordEncoder;

    public record IssuedKey(String keyId, String secret) {}

    @Transactional
    public IssuedKey issueKey(Long merchantId) {
        String keyId = "pk_test_" + randomToken(18);
        String secret = "sk_test_" + randomToken(24);

        ApiCredential credential = new ApiCredential();
        credential.setKeyId(keyId);
        credential.setSecretHash(passwordEncoder.encode(secret));
        credential.setMerchantId(merchantId);
        credential.setStatus("active");
        repository.save(credential);

        return new IssuedKey(keyId, secret);
    }

    @Transactional(readOnly = true)
    public Optional<Long> authenticate(String keyId, String presentedSecret) {
        if (keyId == null || presentedSecret == null) {
            return Optional.empty();
        }
        return repository.findByKeyId(keyId)
                .filter(c -> "active".equalsIgnoreCase(c.getStatus()))
                .filter(c -> passwordEncoder.matches(presentedSecret, c.getSecretHash()))
                .map(ApiCredential::getMerchantId);
    }

    private String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return URL_ENCODER.encodeToString(buf);
    }
}
