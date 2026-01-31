package com.fraud.transaction.api;

import com.fraud.transaction.domain.PaymentMethod;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionResponse(
        String transactionId,
        String userId,
        BigDecimal amount,
        String currency,
        String merchantId,
        String country,
        PaymentMethod paymentMethod,
        Instant createdAt
) {
}
