package com.kimpay.payment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "payment.kafka")
public class KafkaPaymentProperties {
    private boolean enabled = true;
    private String paymentTopic = "payment.events";
}
