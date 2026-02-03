package com.fraud.detection.events;

import com.fraud.detection.model.PaymentMethod;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionCreatedEvent(
        String eventId,
        Instant occurredAt,
        String transactionId,
        String userId,
        BigDecimal amount,
        String currency,
        String merchantId,
        String country,
        PaymentMethod paymentMethod
) {
}
