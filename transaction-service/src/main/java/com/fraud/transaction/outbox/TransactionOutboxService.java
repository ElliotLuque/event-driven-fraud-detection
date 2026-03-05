package com.fraud.transaction.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraud.transaction.events.TransactionCreatedEvent;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class TransactionOutboxService {

    private final TransactionOutboxRepository transactionOutboxRepository;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final Tracer tracer;

    public TransactionOutboxService(
            TransactionOutboxRepository transactionOutboxRepository,
            ObjectMapper objectMapper,
            Tracer tracer,
            @Value("${app.kafka.topics.transactions-created}") String topic
    ) {
        this.transactionOutboxRepository = transactionOutboxRepository;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
        this.topic = topic;
    }

    public void enqueue(TransactionCreatedEvent event) {
        Instant createdAt = event.occurredAt() != null ? event.occurredAt() : Instant.now();
        JsonNode payload = serialize(event);
        TraceHeaders traceHeaders = resolveTraceHeaders(event.traceId());

        TransactionOutboxEvent outboxEvent = TransactionOutboxEvent.pending(
                UUID.randomUUID().toString(),
                event.eventId(),
                topic,
                event.transactionId(),
                payload,
                traceHeaders.traceParent(),
                traceHeaders.baggage(),
                createdAt
        );
        transactionOutboxRepository.save(outboxEvent);
    }

    private TraceHeaders resolveTraceHeaders(String eventTraceId) {
        Span currentSpan = tracer.currentSpan();
        String traceId = null;
        String spanId = null;
        String traceFlags = "01";

        if (currentSpan != null && currentSpan.context() != null) {
            traceId = normalizeHex(currentSpan.context().traceId(), 32);
            spanId = normalizeHex(currentSpan.context().spanId(), 16);
            Boolean sampled = currentSpan.context().sampled();
            if (sampled != null) {
                traceFlags = sampled ? "01" : "00";
            }
        }

        if (traceId == null) {
            traceId = normalizeHex(eventTraceId, 32);
        }
        if (spanId == null) {
            spanId = normalizeHex(MDC.get("spanId"), 16);
        }
        if (spanId == null && traceId != null) {
            spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        String baggage = trimToNull(MDC.get("baggage"));

        if (traceId == null || spanId == null) {
            return new TraceHeaders(null, baggage);
        }
        return new TraceHeaders("00-" + traceId + "-" + spanId + "-" + traceFlags, baggage);
    }

    private String normalizeHex(String value, int expectedLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace("-", "");
        if (normalized.length() != expectedLength || !normalized.matches("[0-9a-f]+")) {
            return null;
        }
        return normalized;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record TraceHeaders(String traceParent, String baggage) {
    }

    private JsonNode serialize(TransactionCreatedEvent event) {
        return objectMapper.valueToTree(event);
    }
}
