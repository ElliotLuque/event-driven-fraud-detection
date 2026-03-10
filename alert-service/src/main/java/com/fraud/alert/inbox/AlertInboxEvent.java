package com.fraud.alert.inbox;

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
        name = "alert_inbox",
        indexes = {
                @Index(name = "idx_alert_inbox_status_received", columnList = "status,received_at"),
                @Index(name = "idx_alert_inbox_status_processed", columnList = "status,processed_at")
        }
)
public class AlertInboxEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private String transactionId;

    @Column(name = "trace_id", length = 64, updatable = false)
    private String traceId;

    @Column(name = "trace_parent", length = 128, updatable = false)
    private String traceParent;

    @Column(name = "baggage", length = 2048, updatable = false)
    private String baggage;

    @Column(name = "payload", nullable = false, columnDefinition = "text", updatable = false)
    private String payload;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AlertInboxStatus status;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected AlertInboxEvent() {
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

    public String getTraceParent() {
        return traceParent;
    }

    public String getBaggage() {
        return baggage;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public AlertInboxStatus getStatus() {
        return status;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public boolean isProcessed() {
        return this.status == AlertInboxStatus.PROCESSED;
    }
}
