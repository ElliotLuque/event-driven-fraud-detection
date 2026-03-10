package com.fraud.alert.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraud.alert.events.FraudDetectedEvent;
import com.fraud.alert.inbox.AlertInboxRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class AlertInboxIngestionService {

    private final AlertInboxRepository alertInboxRepository;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;

    public AlertInboxIngestionService(
            AlertInboxRepository alertInboxRepository,
            ObjectMapper objectMapper,
            Tracer tracer
    ) {
        this.alertInboxRepository = alertInboxRepository;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
    }

    @Transactional
    public boolean ingest(FraudDetectedEvent event) {
        Instant occurredAt = event.occurredAt() != null ? event.occurredAt() : Instant.now();
        String payload = serialize(event);
        TraceHeaders traceHeaders = resolveTraceHeaders(event.traceId());
        int inserted = alertInboxRepository.insertReceivedIfAbsent(
                event.eventId(),
                event.transactionId(),
                event.traceId(),
                traceHeaders.traceParent(),
                traceHeaders.baggage(),
                payload,
                occurredAt,
                Instant.now()
        );
        return inserted > 0;
    }

    private String serialize(FraudDetectedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize fraud event for inbox", ex);
        }
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
}
