package com.fraud.transaction.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface TransactionOutboxRepository extends JpaRepository<TransactionOutboxEvent, String> {

    @Query(value = """
            SELECT *
            FROM transaction_outbox
            WHERE status = 'PENDING'
              AND next_attempt_at <= :now
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<TransactionOutboxEvent> lockPendingBatch(@Param("now") Instant now, @Param("batchSize") int batchSize);

    int deleteByStatusAndPublishedAtBefore(TransactionOutboxStatus status, Instant cutoff);
}
