package com.kimpay.payment.domain.entity;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "merchant_settings")
@Getter
@Setter
@EqualsAndHashCode(of = "id")
public class MerchantSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "setting_name", nullable = false, length = 100)
    private String settingName;

    @Column(name = "setting_value", length = 500)
    private String settingValue;
}

