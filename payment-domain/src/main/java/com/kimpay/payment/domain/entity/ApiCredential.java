package com.kimpay.payment.domain.entity;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * =============================================================================
 * ApiCredential.java
 * -----------------------------------------------------------------------------
 * Project      : payment
 * Module       : com.kimpay.payment.domain.entity
 * Author       : kimho
 * Created On   : 25/05/2026
 * -----------------------------------------------------------------------------
 * Description  : ApiCredential - Merchant API key entity; secret stored only as a BCrypt hash.
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
@Entity
@Getter
@Setter
@EqualsAndHashCode(of = "id", callSuper = false)
@Table(name = "api_credentials")
public class ApiCredential extends AbstractAuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "key_id", nullable = false, unique = true, length = 64)
    private String keyId;

    @Column(name = "secret_hash", nullable = false, length = 100)
    private String secretHash;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;
}
