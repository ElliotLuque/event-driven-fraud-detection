package com.fraud.alert.inbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AlertInboxRepository extends JpaRepository<AlertInboxEvent, String> {

    @Modifying
    @Query(value = """
            INSERT INTO alert_inbox(event_id, transaction_id, trace_id, trace_parent, baggage, payload, occurred_at, received_at, status)
            VALUES (:eventId, :transactionId, :traceId, :traceParent, :baggage, :payload, :occurredAt, :receivedAt, 'RECEIVED')
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertReceivedIfAbsent(
            @Param("eventId") String eventId,
            @Param("transactionId") String transactionId,
            @Param("traceId") String traceId,
            @Param("traceParent") String traceParent,
            @Param("baggage") String baggage,
            @Param("payload") String payload,
            @Param("occurredAt") Instant occurredAt,
            @Param("receivedAt") Instant receivedAt
    );

    @Query(value = """
            SELECT *
            FROM alert_inbox
            WHERE status = 'RECEIVED'
            ORDER BY received_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<AlertInboxEvent> lockNextReceivedBatch(@Param("batchSize") int batchSize);

    @Query(value = """
            SELECT *
            FROM alert_inbox
            WHERE event_id = :eventId
            FOR UPDATE
            """, nativeQuery = true)
    Optional<AlertInboxEvent> lockByEventId(@Param("eventId") String eventId);

    @Modifying
    @Query("UPDATE AlertInboxEvent i SET i.status = com.fraud.alert.inbox.AlertInboxStatus.PROCESSED, i.processedAt = :processedAt WHERE i.eventId = :eventId")
    int markProcessed(@Param("eventId") String eventId, @Param("processedAt") Instant processedAt);

    @Modifying
    @Query("DELETE FROM AlertInboxEvent i WHERE i.status = com.fraud.alert.inbox.AlertInboxStatus.PROCESSED AND i.processedAt < :cutoff")
    int deleteProcessedBefore(@Param("cutoff") Instant cutoff);

    long countByStatus(AlertInboxStatus status);
}
