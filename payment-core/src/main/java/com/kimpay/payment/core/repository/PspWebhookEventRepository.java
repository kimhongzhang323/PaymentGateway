package com.kimpay.payment.core.repository;

import com.kimpay.payment.domain.entity.PspWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PspWebhookEventRepository extends JpaRepository<PspWebhookEvent, Long> {
    boolean existsByEventId(String eventId);
}
