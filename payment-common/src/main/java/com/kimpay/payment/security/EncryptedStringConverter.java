package com.kimpay.payment.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * =============================================================================
 * EncryptedStringConverter.java
 * -----------------------------------------------------------------------------
 * Project      : payment
 * Module       : com.kimpay.payment.security
 * Author       : kimho
 * Created On   : 25/10/2025
 * Last Modified: 5:34 pm
 * -----------------------------------------------------------------------------
 * Description  : EncryptedStringConverter - Core component or utility class.
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
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static final Logger logger = LoggerFactory.getLogger(EncryptedStringConverter.class);
    @Setter
    private static EncryptionService encryptionService;

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) return null;
        try {
            return encryptionService.encrypt(attribute);
        } catch (EncryptionException e) {
            logger.error("Error encrypting attribute", e);
            throw e;
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            return encryptionService.decrypt(dbData);
        } catch (EncryptionException e) {
            logger.error("Error decrypting database data", e);
            throw e;
        }
    }

}
