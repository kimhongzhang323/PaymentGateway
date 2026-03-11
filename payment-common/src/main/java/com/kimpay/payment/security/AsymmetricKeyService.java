package com.kimpay.payment.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * =============================================================================
 * AsymmetricKeyService.java
 * -----------------------------------------------------------------------------
 * Project      : payment
 * Module       : com.kimpay.payment.security
 * Author       : kimho
 * Created On   : 11/03/2026
 * -----------------------------------------------------------------------------
 * Description  : AsymmetricKeyService - Handles RSA key pair tasks.
 * <p>
 * This class provides utility methods for generating keys, signing, and verifying
 * signatures using the RSA algorithm.
 * -----------------------------------------------------------------------------
 * SECURITY NOTICE
 * -----------------------------------------------------------------------------
 * This service handles private keys. Ensure that private keys are NEVER logged
 * and are stored securely in a KMS or similar system.
 * =============================================================================
 */
public class AsymmetricKeyService {
    private static final Logger log = LoggerFactory.getLogger(AsymmetricKeyService.class);
    private static final String RSA = "RSA";
    private static final String SHA256_WITH_RSA = "SHA256withRSA";

    /**
     * Generates a new RSA KeyPair (2048-bit).
     * @return a new KeyPair object.
     */
    public KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(RSA);
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new KeyManagementException("Error generating RSA key pair", e);
        }
    }

    /**
     * Signs the given input string using the private key.
     * @param input the plain data to sign.
     * @param privateKey the private key for signing.
     * @return the Base64-encoded signature.
     */
    public String sign(String input, PrivateKey privateKey) {
        try {
            Signature signature = Signature.getInstance(SHA256_WITH_RSA);
            signature.initSign(privateKey);
            signature.update(input.getBytes(StandardCharsets.UTF_8));
            byte[] signedData = signature.sign();
            return Base64.getEncoder().encodeToString(signedData);
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            throw new KeyManagementException("Error signing data", e);
        }
    }

    /**
     * Verifies the given signature against the input string using the public key.
     * @param input the plain data that was signed.
     * @param signatureBase64 the Base64-encoded signature.
     * @param publicKey the public key to verify.
     * @return true if the signature is valid, false otherwise.
     */
    public boolean verify(String input, String signatureBase64, PublicKey publicKey) {
        try {
            Signature signature = Signature.getInstance(SHA256_WITH_RSA);
            signature.initVerify(publicKey);
            signature.update(input.getBytes(StandardCharsets.UTF_8));
            byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);
            return signature.verify(signatureBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            log.error("Error verifying signature", e);
            return false;
        }
    }

    /**
     * Loads a PrivateKey from a Base64-encoded string (PKCS#8).
     * @param privateKeyBase64 Base64-encoded PKCS#8 private key.
     * @return the PrivateKey object.
     */
    public PrivateKey loadPrivateKey(String privateKeyBase64) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(privateKeyBase64);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance(RSA);
            return kf.generatePrivate(spec);
        } catch (Exception e) {
            throw new KeyManagementException("Error loading private key", e);
        }
    }

    /**
     * Loads a PublicKey from a Base64-encoded string (X.509).
     * @param publicKeyBase64 Base64-encoded X.509 public key.
     * @return the PublicKey object.
     */
    public PublicKey loadPublicKey(String publicKeyBase64) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance(RSA);
            return kf.generatePublic(spec);
        } catch (Exception e) {
            throw new KeyManagementException("Error loading public key", e);
        }
    }

    /**
     * Converts a key's encoded format to a Base64 string.
     * @param key the key to encode.
     * @return the Base64 representation.
     */
    public String encodeKey(Key key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }
}
