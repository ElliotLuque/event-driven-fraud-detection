package com.fraud.detection.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "user_transaction_history",
        indexes = {
                @Index(name = "idx_history_user_occurred", columnList = "userId, occurredAt"),
                @Index(name = "idx_history_transaction", columnList = "transactionId", unique = true)
        }
)
public class UserTransactionHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String transactionId;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private String merchantId;

    @Column(nullable = false, length = 2)
    private String country;

    @Column(nullable = false)
    private Instant occurredAt;

    protected UserTransactionHistory() {
    }

    public UserTransactionHistory(
            String transactionId,
            String userId,
            BigDecimal amount,
            String currency,
            String merchantId,
            String country,
            Instant occurredAt
    ) {
        this.transactionId = transactionId;
        this.userId = userId;
        this.amount = amount;
        this.currency = currency;
        this.merchantId = merchantId;
        this.country = country;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getUserId() {
        return userId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getCountry() {
        return country;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
