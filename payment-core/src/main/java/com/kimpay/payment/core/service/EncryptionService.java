package com.kimpay.payment.core.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
@Service
public class EncryptionService {

    private final String algorithm;
    private final byte[] key;
    private final int tagLength;
    private final int ivSize;

    public EncryptionService(
            @Value("${payment.encryption.algorithm:AES/GCM/NoPadding}") String algorithm,
            @Value("${payment.encryption.key-base64:}") String keyBase64,
            @Value("${payment.encryption.iv-size:12}") int ivSize,
            @Value("${payment.encryption.tag-length:128}") int tagLength) {
        this.algorithm = algorithm;
        this.ivSize = ivSize;
        this.tagLength = tagLength;
        
        if (keyBase64 == null || keyBase64.isEmpty()) {
            // Default key for development, should be injected in production
            this.key = "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8);
        } else {
            this.key = Base64.getDecoder().decode(keyBase64);
        }
    }

    public String encrypt(String plainText) {
        try {
            byte[] iv = new byte[ivSize];
            new SecureRandom().nextBytes(iv);
            
            Cipher cipher = Cipher.getInstance(algorithm);
            GCMParameterSpec spec = new GCMParameterSpec(tagLength, iv);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), spec);
            
            byte[] encryptedText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            
            byte[] combined = new byte[iv.length + encryptedText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encryptedText, 0, combined, iv.length, encryptedText.length);
            
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("Encryption failed", e);
            throw new RuntimeException("Encryption error", e);
        }
    }

    public String decrypt(String encryptedBase64) {
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedBase64);
            
            byte[] iv = new byte[ivSize];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            
            byte[] encryptedText = new byte[combined.length - iv.length];
            System.arraycopy(combined, iv.length, encryptedText, 0, encryptedText.length);
            
            Cipher cipher = Cipher.getInstance(algorithm);
            GCMParameterSpec spec = new GCMParameterSpec(tagLength, iv);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), spec);
            
            byte[] decryptedText = cipher.doFinal(encryptedText);
            return new String(decryptedText, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Decryption failed", e);
            throw new RuntimeException("Decryption error", e);
        }
    }
}
