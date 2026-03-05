package com.fraud.transaction.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(
        name = "transaction_outbox",
        indexes = {
                @Index(name = "idx_transaction_outbox_pending", columnList = "status,next_attempt_at,created_at"),
                @Index(name = "idx_transaction_outbox_event_id", columnList = "event_id", unique = true)
        }
)
public class TransactionOutboxEvent {

    @Id
    private String id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "topic", nullable = false, updatable = false)
    private String topic;

    @Column(name = "event_key", nullable = false, updatable = false)
    private String eventKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb", updatable = false)
    private JsonNode payload;

    @Column(name = "trace_parent", length = 128, updatable = false)
    private String traceParent;

    @Column(name = "baggage", length = 2048, updatable = false)
    private String baggage;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TransactionOutboxStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", length = 512)
    private String lastError;

    protected TransactionOutboxEvent() {
    }

    private TransactionOutboxEvent(
            String id,
            String eventId,
            String topic,
            String eventKey,
            JsonNode payload,
            String traceParent,
            String baggage,
            Instant createdAt
    ) {
        this.id = id;
        this.eventId = eventId;
        this.topic = topic;
        this.eventKey = eventKey;
        this.payload = payload;
        this.traceParent = traceParent;
        this.baggage = baggage;
        this.status = TransactionOutboxStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = createdAt;
        this.createdAt = createdAt;
    }

    public static TransactionOutboxEvent pending(
            String id,
            String eventId,
            String topic,
            String eventKey,
            JsonNode payload,
            String traceParent,
            String baggage,
            Instant createdAt
    ) {
        return new TransactionOutboxEvent(id, eventId, topic, eventKey, payload, traceParent, baggage, createdAt);
    }

    public void markPublished(Instant publishedAt) {
        this.status = TransactionOutboxStatus.PUBLISHED;
        this.publishedAt = publishedAt;
        this.lastError = null;
    }

    public void markFailed(String error, Instant nextAttemptAt) {
        this.attempts = this.attempts + 1;
        this.status = TransactionOutboxStatus.PENDING;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = truncate(error);
    }

    public String getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getTopic() {
        return topic;
    }

    public String getEventKey() {
        return eventKey;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public String getTraceParent() {
        return traceParent;
    }

    public String getBaggage() {
        return baggage;
    }

    public TransactionOutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() <= 512) {
            return value;
        }
        return value.substring(0, 512);
    }
}
