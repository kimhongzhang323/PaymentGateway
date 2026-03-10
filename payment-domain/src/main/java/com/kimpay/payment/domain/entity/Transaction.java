package com.kimpay.payment.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * =============================================================================
 * Transaction.java
 * -----------------------------------------------------------------------------
 * Project      : payment
 * Module       : payment-domain
 * Author       : kimho
 * Created On   : 25/10/2025
 * Last Modified: 1:53 pm
 * -----------------------------------------------------------------------------
 * Description  : JPA Entity representing a payment transaction in the system.
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
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "payment_method_id")
    private Long paymentMethodId;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void authorize() {
        this.status = PaymentStatus.AUTHORIZED.name();
    }

    public void capture() {
        if (!PaymentStatus.AUTHORIZED.name().equals(this.status)) {
            throw new IllegalStateException("Only AUTHORIZED transactions can be captured");
        }
        this.status = PaymentStatus.CAPTURED.name();
    }

    public void markFailed() {
        this.status = PaymentStatus.FAILED.name();
    }

    public void refund() {
        if (!PaymentStatus.CAPTURED.name().equals(this.status) && !PaymentStatus.PARTIALLY_REFUNDED.name().equals(this.status)) {
            throw new IllegalStateException("Only CAPTURED or PARTIALLY_REFUNDED transactions can be refunded");
        }
        this.status = PaymentStatus.REFUNDED.name();
    }

    public void partialRefund() {
        if (!PaymentStatus.CAPTURED.name().equals(this.status) && !PaymentStatus.PARTIALLY_REFUNDED.name().equals(this.status)) {
            throw new IllegalStateException("Only CAPTURED or PARTIALLY_REFUNDED transactions can be partially refunded");
        }
        this.status = PaymentStatus.PARTIALLY_REFUNDED.name();
    }

    // 更新时间
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (this.status == null || this.status.isBlank()) {
            this.status = PaymentStatus.AUTHORIZED.name();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
