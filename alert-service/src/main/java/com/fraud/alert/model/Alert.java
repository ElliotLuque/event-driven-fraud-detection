package com.fraud.alert.model;

import com.fraud.alert.events.FraudDetectedEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.StringJoiner;
import java.util.UUID;

@Entity
@Table(
        name = "alerts",
        indexes = {
                @Index(name = "idx_alert_user", columnList = "userId"),
                @Index(name = "idx_alert_transaction", columnList = "transactionId")
        }
)
public class Alert {

    @Id
    private String id;

    @Column(nullable = false)
    private String transactionId;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private int riskScore;

    @Column(nullable = false, length = 2000)
    private String reasons;

    @Column(nullable = false)
    private Instant createdAt;

    protected Alert() {
    }

    public Alert(
            String id,
            String transactionId,
            String userId,
            int riskScore,
            String reasons,
            Instant createdAt
    ) {
        this.id = id;
        this.transactionId = transactionId;
        this.userId = userId;
        this.riskScore = riskScore;
        this.reasons = reasons;
        this.createdAt = createdAt;
    }

    public static Alert fromEvent(FraudDetectedEvent event, Instant now) {
        return new Alert(
                UUID.randomUUID().toString(),
                event.transactionId(),
                event.userId(),
                event.riskScore(),
                joinReasons(event),
                now
        );
    }

    private static String joinReasons(FraudDetectedEvent event) {
        StringJoiner joiner = new StringJoiner(",");
        event.reasons().forEach(joiner::add);
        return joiner.toString();
    }

    public String getId() {
        return id;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getUserId() {
        return userId;
    }

    public int getRiskScore() {
        return riskScore;
    }

    public String getReasons() {
        return reasons;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
