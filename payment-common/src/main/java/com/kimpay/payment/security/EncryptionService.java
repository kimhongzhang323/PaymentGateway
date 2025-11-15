package com.kimpay.payment.security;

import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * =============================================================================
 * EncryptionService.java
 * -----------------------------------------------------------------------------
 * Project      : payment
 * Module       : com.kimpay.payment.security
 * Author       : kimho
 * Created On   : 25/10/2025
 * Last Modified: 5:21 pm
 * -----------------------------------------------------------------------------
 * Description  : EncryptionService - Core component or utility class.
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

@Setter
public class EncryptionService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String AES = "AES";

    private final KeyProvider keyProvider;
    private final String transformation;
    private final int ivSize;
    private final int taglengthBits;

    public EncryptionService(KeyProvider keyProvider,
                             String transformation,
                             int ivSize,
                             int taglengthBits) {
        this.keyProvider = keyProvider;
        this.transformation = transformation;
        this.ivSize = ivSize;
        this.taglengthBits = taglengthBits;
    }

    // Encrypts the given plain text and returns the cipher text in Base64 encoding
    public String encrypt(String plainText) {
        try{
            byte[] key = keyProvider.getDataEncryptionKey();
            byte[] iv = new byte[ivSize];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(transformation);
            SecretKeySpec keySpec = new SecretKeySpec(key, AES);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(taglengthBits, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);

            return java.util.Base64.getEncoder().encodeToString(combined);
        } catch (NoSuchPaddingException | NoSuchAlgorithmException | InvalidAlgorithmParameterException |
                 InvalidKeyException | IllegalBlockSizeException | BadPaddingException e) {
            throw new RuntimeException(e);
        } catch (KeyProviderException e) {
            throw new RuntimeException(String.valueOf(e));
        }
    }

    // Decrypts the given Base64 encoded cipher text and returns the plain text
    public String decrypt(String cipherText) {
        try {
            byte[] key = keyProvider.getDataEncryptionKey();
            byte[] decoded = java.util.Base64.getDecoder().decode(cipherText);

            byte[] iv = new byte[ivSize];
            System.arraycopy(decoded, 0, iv, 0, iv.length);
            byte[] actualCipherText = new byte[decoded.length - iv.length];
            System.arraycopy(decoded, iv.length, actualCipherText, 0, actualCipherText.length);

            // Initialize cipher for decryption
            Cipher cipher = Cipher.getInstance(transformation);
            SecretKeySpec keySpec = new SecretKeySpec(key, AES);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(taglengthBits, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

            byte[] plainTextBytes = cipher.doFinal(actualCipherText);
            return new String(plainTextBytes, StandardCharsets.UTF_8);
        } catch (NoSuchPaddingException | NoSuchAlgorithmException | InvalidAlgorithmParameterException |
                 InvalidKeyException | IllegalBlockSizeException | BadPaddingException e) {
            throw new RuntimeException(e);
        } catch (KeyProviderException e) {
            throw new RuntimeException(String.valueOf(e));
        }
    }

}
