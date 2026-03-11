package com.kimpay.payment.core.service;

import com.kimpay.payment.core.repository.MerchantRepository;
import com.kimpay.payment.domain.entity.Merchant;
import com.kimpay.payment.security.AsymmetricKeyService;
import com.kimpay.payment.security.KeyManagementException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.PublicKey;
import java.util.Optional;

/**
 * =============================================================================
 * SignatureVerificationService.java
 * -----------------------------------------------------------------------------
 * Project      : payment
 * Module       : com.kimpay.payment.core.service
 * Author       : kimho
 * Created On   : 11/03/2026
 * -----------------------------------------------------------------------------
 * Description  : SignatureVerificationService - Verifies incoming request
 * signatures using the merchant's stored public key.
 * -----------------------------------------------------------------------------
 */
@Service
@RequiredArgsConstructor
public class SignatureVerificationService {
    private static final Logger log = LoggerFactory.getLogger(SignatureVerificationService.class);

    private final MerchantRepository merchantRepository;
    private final AsymmetricKeyService asymmetricKeyService;

    /**
     * Verifies the signature of a request from a merchant.
     * @param merchantId ID of the merchant.
     * @param plainData the raw request data (e.g., JSON string).
     * @param signatureBase64 the Base64-encoded signature of the data.
     * @return true if valid, false if invalid or merchant not found.
     */
    public boolean verifyMerchantSignature(Long merchantId, String plainData, String signatureBase64) {
        if (merchantId == null) {
            log.warn("merchantId is null");
            return false;
        }
        Optional<Merchant> merchantOpt = merchantRepository.findById(merchantId);
        if (merchantOpt.isEmpty()) {
            log.warn("Merchant not found for ID: {}", merchantId);
            return false;
        }

        Merchant merchant = merchantOpt.get();
        String publicKeyBase64 = merchant.getPublicKey();

        if (publicKeyBase64 == null || publicKeyBase64.isEmpty()) {
            log.warn("Public key not found for merchant: {}", merchantId);
            return false;
        }

        try {
            PublicKey publicKey = asymmetricKeyService.loadPublicKey(publicKeyBase64);
            return asymmetricKeyService.verify(plainData, signatureBase64, publicKey);
        } catch (KeyManagementException e) {
            log.error("Failed to load public key for merchant: {}", merchantId, e);
            return false;
        }
    }
}
