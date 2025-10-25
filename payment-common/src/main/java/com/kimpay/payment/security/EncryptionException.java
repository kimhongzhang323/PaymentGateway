package com.kimpay.payment.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * =============================================================================
 * EncryptionException.java
 * -----------------------------------------------------------------------------
 * Project      : payment
 * Module       : com.kimpay.payment.security
 * Author       : kimho
 * Created On   : 25/10/2025
 * Last Modified: 5:29 pm
 * -----------------------------------------------------------------------------
 * Description  : EncryptionException - Core component or utility class.
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
public class EncryptionException extends RuntimeException {
    public EncryptionException(String msg){
        super(msg);
    }
    public EncryptionException(String msg, Throwable t) { super(msg, t); }
}


