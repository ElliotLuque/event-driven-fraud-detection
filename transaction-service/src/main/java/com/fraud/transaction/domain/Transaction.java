package com.fraud.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    private String id;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod paymentMethod;

    @Column(nullable = false)
    private Instant createdAt;

    protected Transaction() {
    }

    public Transaction(
            String id,
            String userId,
            BigDecimal amount,
            String currency,
            String merchantId,
            String country,
            PaymentMethod paymentMethod,
            Instant createdAt
    ) {
        this.id = id;
        this.userId = userId;
        this.amount = amount;
        this.currency = currency;
        this.merchantId = merchantId;
        this.country = country;
        this.paymentMethod = paymentMethod;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
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

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
