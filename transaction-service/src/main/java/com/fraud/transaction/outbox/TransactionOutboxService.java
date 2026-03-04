package com.fraud.transaction.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraud.transaction.events.TransactionCreatedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class TransactionOutboxService {

    private final TransactionOutboxRepository transactionOutboxRepository;
    private final ObjectMapper objectMapper;
    private final String topic;

    public TransactionOutboxService(
            TransactionOutboxRepository transactionOutboxRepository,
            ObjectMapper objectMapper,
            @Value("${app.kafka.topics.transactions-created}") String topic
    ) {
        this.transactionOutboxRepository = transactionOutboxRepository;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    public void enqueue(TransactionCreatedEvent event) {
        Instant createdAt = event.occurredAt() != null ? event.occurredAt() : Instant.now();
        String payload = serialize(event);

        TransactionOutboxEvent outboxEvent = TransactionOutboxEvent.pending(
                UUID.randomUUID().toString(),
                event.eventId(),
                topic,
                event.transactionId(),
                payload,
                createdAt
        );
        transactionOutboxRepository.save(outboxEvent);
    }

    private String serialize(TransactionCreatedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize transaction outbox payload", ex);
        }
    }
}
