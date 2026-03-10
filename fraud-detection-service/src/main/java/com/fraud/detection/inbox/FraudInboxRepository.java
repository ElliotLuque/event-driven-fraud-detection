package com.fraud.detection.inbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FraudInboxRepository extends JpaRepository<FraudInboxEvent, String> {

    @Modifying
    @Query(value = """
            INSERT INTO fraud_inbox(event_id, transaction_id, trace_id, payload, occurred_at, received_at, status)
            VALUES (:eventId, :transactionId, :traceId, :payload, :occurredAt, :receivedAt, 'RECEIVED')
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertReceivedIfAbsent(
            @Param("eventId") String eventId,
            @Param("transactionId") String transactionId,
            @Param("traceId") String traceId,
            @Param("payload") String payload,
            @Param("occurredAt") Instant occurredAt,
            @Param("receivedAt") Instant receivedAt
    );

    @Query(value = """
            SELECT *
            FROM fraud_inbox
            WHERE status = 'RECEIVED'
            ORDER BY received_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<FraudInboxEvent> lockNextReceivedBatch(@Param("batchSize") int batchSize);

    @Query(value = """
            SELECT *
            FROM fraud_inbox
            WHERE event_id = :eventId
            FOR UPDATE
            """, nativeQuery = true)
    Optional<FraudInboxEvent> lockByEventId(@Param("eventId") String eventId);

    @Modifying
    @Query("UPDATE FraudInboxEvent i SET i.status = com.fraud.detection.inbox.FraudInboxStatus.PROCESSED, i.processedAt = :processedAt WHERE i.eventId = :eventId")
    int markProcessed(@Param("eventId") String eventId, @Param("processedAt") Instant processedAt);

    @Modifying
    @Query("DELETE FROM FraudInboxEvent i WHERE i.status = com.fraud.detection.inbox.FraudInboxStatus.PROCESSED AND i.processedAt < :cutoff")
    int deleteProcessedBefore(@Param("cutoff") Instant cutoff);

    long countByStatus(FraudInboxStatus status);
}
