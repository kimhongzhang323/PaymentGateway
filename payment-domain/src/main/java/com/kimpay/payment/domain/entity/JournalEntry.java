package com.kimpay.payment.domain.entity;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "journal_entries")
@Getter
@Setter
@EqualsAndHashCode(of = "id", callSuper = false)
public class JournalEntry extends AbstractCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Column(name = "event_type", nullable = false, length = 20)
    private String eventType;

    @Column(length = 255)
    private String description;
}
