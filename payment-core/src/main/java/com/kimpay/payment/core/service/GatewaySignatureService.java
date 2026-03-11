package com.kimpay.payment.core.service;

import com.kimpay.payment.security.AsymmetricKeyService;
import com.kimpay.payment.security.KeyManagementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.PrivateKey;

/**
 * =============================================================================
 * GatewaySignatureService.java
 * -----------------------------------------------------------------------------
 * Project      : payment
 * Module       : com.kimpay.payment.core.service
 * Author       : kimho
 * Created On   : 11/03/2026
 * -----------------------------------------------------------------------------
 * Description  : GatewaySignatureService - Signs outgoing responses using the
 * gateway's private key.
 * -----------------------------------------------------------------------------
 */
@Service
public class GatewaySignatureService {
    private static final Logger log = LoggerFactory.getLogger(GatewaySignatureService.class);

    private final AsymmetricKeyService asymmetricKeyService;
    private final PrivateKey privateKey;

    public GatewaySignatureService(
            AsymmetricKeyService asymmetricKeyService,
            @Value("${payment.gateway.private-key:}") String privateKeyBase64
    ) {
        this.asymmetricKeyService = asymmetricKeyService;
        if (privateKeyBase64 == null || privateKeyBase64.isEmpty()) {
            log.warn("Gateway private key not provided. Signing will be disabled.");
            this.privateKey = null;
        } else {
            this.privateKey = asymmetricKeyService.loadPrivateKey(privateKeyBase64);
        }
    }

    /**
     * Signs the given data string with the gateway's private key.
     * @param plainData the data to sign.
     * @return the Base64-encoded signature, or null if signing is disabled.
     */
    public String signResponse(String plainData) {
        if (privateKey == null) {
            log.error("Cannot sign response: Private key is missing.");
            return null;
        }
        try {
            return asymmetricKeyService.sign(plainData, privateKey);
        } catch (KeyManagementException e) {
            log.error("Error signing gateway response", e);
            return null;
        }
    }
}
