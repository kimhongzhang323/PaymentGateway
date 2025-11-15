package com.kimpay.payment.domain.entity;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "transaction_fees")
@Getter
@Setter
@EqualsAndHashCode(of = "id")
public class TransactionFee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Column(name = "fee_type", nullable = false, length = 50)
    private String feeType;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;
}

