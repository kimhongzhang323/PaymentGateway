package com.kimpay.payment.config;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@EnableConfigurationProperties(KafkaPaymentProperties.class)
@RequiredArgsConstructor
public class KafkaConfig {

    private final KafkaPaymentProperties properties;

    @Bean
    public NewTopic paymentEventsTopic() {
        return TopicBuilder.name(properties.getPaymentTopic())
                .partitions(3)
                .replicas(1)
                .build();
    }
}
