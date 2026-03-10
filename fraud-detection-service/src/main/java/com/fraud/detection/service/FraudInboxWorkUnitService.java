package com.fraud.detection.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraud.detection.events.TransactionCreatedEvent;
import com.fraud.detection.inbox.FraudInboxEvent;
import com.fraud.detection.inbox.FraudInboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class FraudInboxWorkUnitService {

    private final FraudInboxRepository fraudInboxRepository;
    private final FraudDetectionService fraudDetectionService;
    private final ObjectMapper objectMapper;

    public FraudInboxWorkUnitService(
            FraudInboxRepository fraudInboxRepository,
            FraudDetectionService fraudDetectionService,
            ObjectMapper objectMapper
    ) {
        this.fraudInboxRepository = fraudInboxRepository;
        this.fraudDetectionService = fraudDetectionService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public int processNextBatch(int claimSize) {
        int effectiveClaimSize = Math.max(1, claimSize);
        List<FraudInboxEvent> batch = fraudInboxRepository.lockNextReceivedBatch(effectiveClaimSize);
        if (batch.isEmpty()) {
            return 0;
        }

        int processed = 0;
        for (FraudInboxEvent inboxEvent : batch) {
            TransactionCreatedEvent event = deserialize(inboxEvent.getPayload(), inboxEvent.getEventId());
            fraudDetectionService.processIngestedEvent(event, inboxEvent.getOccurredAt());
            processed++;
        }
        return processed;
    }

    private TransactionCreatedEvent deserialize(String payload, String eventId) {
        try {
            return objectMapper.readValue(payload, TransactionCreatedEvent.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to deserialize inbox payload for eventId=" + eventId, ex);
        }
    }
}
