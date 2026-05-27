package com.kimpay.payment.core.repository;

import com.kimpay.payment.domain.entity.WebhookDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    @Query("""
        SELECT d FROM WebhookDelivery d
        WHERE d.status IN ('PENDING', 'FAILED')
        AND (d.nextRetryAt IS NULL OR d.nextRetryAt <= :now)
        ORDER BY d.nextRetryAt ASC NULLS FIRST
        LIMIT :limit
    """)
    List<WebhookDelivery> findDueForRetry(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
