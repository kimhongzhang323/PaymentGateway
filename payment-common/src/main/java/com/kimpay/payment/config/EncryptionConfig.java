package com.kimpay.payment.config;

import com.kimpay.payment.security.AsymmetricKeyService;
import com.kimpay.payment.security.EncryptedStringConverter;
import com.kimpay.payment.security.EncryptionService;
import com.kimpay.payment.security.EnvKeyProvider;
import com.kimpay.payment.security.KeyProvider;
import com.kimpay.payment.security.KmsKeyProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * =============================================================================
 * EncryptionConfig.java
 * -----------------------------------------------------------------------------
 * Project      : payment
 * Module       : com.kimpay.payment.security
 * Author       : kimho
 * Created On   : 25/10/2025
 * Last Modified: 5:33 pm
 * -----------------------------------------------------------------------------
 * Description  : EncryptionConfig - Core component or utility class.
 * <p>
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
@Configuration
public class EncryptionConfig {

    /**
     * Selectable data-encryption key source. {@code env} (default) reads a Base64 key
     * from configuration for local / single-host use; {@code kms} unwraps the key via a
     * KMS in shared / deployed environments. Switched with {@code payment.encryption.key-provider}
     * — no code change needed to move to KMS.
     * <p>
     * Selection is done in-bean rather than with {@code @ConditionalOnProperty} to keep
     * payment-common free of a spring-boot-autoconfigure dependency.
     */
    @Bean
    public KeyProvider keyProvider(
            @Value("${payment.encryption.key-provider:env}") String provider,
            @Value("${payment.encryption.key-base64:}") String base64Key
    ) {
        if ("kms".equalsIgnoreCase(provider)) {
            return new KmsKeyProvider();
        }
        return new EnvKeyProvider(base64Key);
    }

    @Bean
    public EncryptionService encryptionService(
            KeyProvider keyProvider,
            @Value("${payment.encryption.algorithm}") String algorithm,
            @Value("${payment.encryption.iv-size}") int ivSize,
            @Value("${payment.encryption.tag-length}") int tagLength
    ) {
        EncryptionService service = new EncryptionService(
                keyProvider, algorithm, ivSize, tagLength
        );
        EncryptedStringConverter.setEncryptionService(service);
        return service;
    }

    @Bean
    public AsymmetricKeyService asymmetricKeyService() {
        return new AsymmetricKeyService();
    }
}


