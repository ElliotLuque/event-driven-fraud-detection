package com.fraud.detection.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface FraudOutboxRepository extends JpaRepository<FraudOutboxEvent, String> {

    @Query(value = """
            SELECT *
            FROM fraud_outbox
            WHERE status = 'PENDING'
              AND next_attempt_at <= :now
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<FraudOutboxEvent> lockPendingBatch(@Param("now") Instant now, @Param("batchSize") int batchSize);

    int deleteByStatusAndPublishedAtBefore(FraudOutboxStatus status, Instant cutoff);
}
