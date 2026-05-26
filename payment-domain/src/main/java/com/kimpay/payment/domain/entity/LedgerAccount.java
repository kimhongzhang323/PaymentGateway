package com.kimpay.payment.domain.entity;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Entity
@Table(
    name = "ledger_accounts",
    uniqueConstraints = @UniqueConstraint(columnNames = {"code", "currency"})
)
@Getter
@Setter
@EqualsAndHashCode(of = "id", callSuper = false)
public class LedgerAccount extends AbstractCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String code;

    @Column(name = "owner_type", nullable = false, length = 30)
    private String ownerType;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(nullable = false, length = 20)
    private String classification;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;
}
