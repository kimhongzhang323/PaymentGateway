package com.kimpay.payment.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;

/**
 * =============================================================================
 * EnvKeyProvider.java
 * -----------------------------------------------------------------------------
 * Project      : payment
 * Module       : com.kimpay.payment.security
 * Author       : kimho
 * Created On   : 25/10/2025
 * Last Modified: 5:26 pm
 * -----------------------------------------------------------------------------
 * Description  : EnvKeyProvider - Core component or utility class.
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
public class EnvKeyProvider implements KeyProvider {

    private final byte[] key;

    public EnvKeyProvider(String base64Key) {
        if (base64Key == null) {
            throw new IllegalArgumentException("ENCRYPTION_KEY not provided");
        }
        this.key = Base64.getDecoder().decode(base64Key);
    }

    @Override
    public byte[] getDataEncryptionKey() {
        return key.clone(); // return defensive copy
    }
}
