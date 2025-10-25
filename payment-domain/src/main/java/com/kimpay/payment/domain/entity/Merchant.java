package com.kimpay.payment.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

/**
 * =============================================================================
 * Merchant.java
 * -----------------------------------------------------------------------------
 * Project      : payment
 * Module       : com.kimpay.payment.domain.entity
 * Author       : kimho
 * Created On   : 25/10/2025
 * Last Modified: 1:56 pm
 * -----------------------------------------------------------------------------
 * Description  : Merchant - Core component or utility class.
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
@Table(name = "merchants")
public class Merchant {

    @Id
    private String merchantId;
    private String name;
    private String apiKey;
    private String secretKey;
    private boolean active;
    private LocalDateTime createdAt;

}
