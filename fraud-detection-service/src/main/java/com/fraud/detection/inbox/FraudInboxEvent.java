package com.fraud.detection.inbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(
        name = "fraud_inbox",
        indexes = {
                @Index(name = "idx_fraud_inbox_status_received", columnList = "status,received_at"),
                @Index(name = "idx_fraud_inbox_status_processed", columnList = "status,processed_at")
        }
)
public class FraudInboxEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private String transactionId;

    @Column(name = "trace_id", length = 64, updatable = false)
    private String traceId;

    @Column(name = "payload", nullable = false, columnDefinition = "text", updatable = false)
    private String payload;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private FraudInboxStatus status;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected FraudInboxEvent() {
    }

    private FraudInboxEvent(
            String eventId,
            String transactionId,
            String traceId,
            String payload,
            Instant occurredAt,
            Instant receivedAt
    ) {
        this.eventId = eventId;
        this.transactionId = transactionId;
        this.traceId = traceId;
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
        this.status = FraudInboxStatus.RECEIVED;
    }

    public static FraudInboxEvent received(
            String eventId,
            String transactionId,
            String traceId,
            String payload,
            Instant occurredAt,
            Instant receivedAt
    ) {
        return new FraudInboxEvent(eventId, transactionId, traceId, payload, occurredAt, receivedAt);
    }

    public void markProcessed(Instant processedAt) {
        this.status = FraudInboxStatus.PROCESSED;
        this.processedAt = processedAt;
    }

    public boolean isProcessed() {
        return this.status == FraudInboxStatus.PROCESSED;
    }

    public String getEventId() {
        return eventId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public FraudInboxStatus getStatus() {
        return status;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
