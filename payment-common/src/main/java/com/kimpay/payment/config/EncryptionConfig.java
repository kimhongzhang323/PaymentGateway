package com.kimpay.payment.config;

import com.kimpay.payment.security.EncryptedStringConverter;
import com.kimpay.payment.security.EncryptionService;
import com.kimpay.payment.security.EnvKeyProvider;
import com.kimpay.payment.security.KeyProvider;
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

    @Bean
    public EncryptionService encryptionService(
            @Value("${payment.encryption.key-base64}") String base64Key,
            @Value("${payment.encryption.algorithm}") String algorithm,
            @Value("${payment.encryption.iv-size}") int ivSize,
            @Value("${payment.encryption.tag-length}") int tagLength
    ) {
        KeyProvider keyProvider = new EnvKeyProvider(base64Key);
        EncryptionService service = new EncryptionService(
                keyProvider, algorithm, ivSize, tagLength
        );
        EncryptedStringConverter.setEncryptionService(service);
        return service;
    }
}


