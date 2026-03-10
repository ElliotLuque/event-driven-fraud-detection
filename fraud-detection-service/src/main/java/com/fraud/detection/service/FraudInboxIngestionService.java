package com.fraud.detection.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraud.detection.events.TransactionCreatedEvent;
import com.fraud.detection.inbox.FraudInboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class FraudInboxIngestionService {

    private final FraudInboxRepository fraudInboxRepository;
    private final FraudDetectionMetrics fraudDetectionMetrics;
    private final ObjectMapper objectMapper;

    public FraudInboxIngestionService(
            FraudInboxRepository fraudInboxRepository,
            FraudDetectionMetrics fraudDetectionMetrics,
            ObjectMapper objectMapper
    ) {
        this.fraudInboxRepository = fraudInboxRepository;
        this.fraudDetectionMetrics = fraudDetectionMetrics;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public boolean ingest(TransactionCreatedEvent event) {
        fraudDetectionMetrics.recordEventConsumed();

        Instant occurredAt = event.occurredAt() != null ? event.occurredAt() : Instant.now();
        String payload = serialize(event);
        int inserted = fraudInboxRepository.insertReceivedIfAbsent(
                event.eventId(),
                event.transactionId(),
                event.traceId(),
                payload,
                occurredAt,
                Instant.now()
        );
        return inserted > 0;
    }

    private String serialize(TransactionCreatedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize transaction event for inbox", ex);
        }
    }
}
