package com.fraud.alert.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraud.alert.events.FraudDetectedEvent;
import com.fraud.alert.inbox.AlertInboxEvent;
import com.fraud.alert.inbox.AlertInboxRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AlertInboxWorkUnitService {

    private final AlertInboxRepository alertInboxRepository;
    private final AlertProcessingService alertProcessingService;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Propagator propagator;

    public AlertInboxWorkUnitService(
            AlertInboxRepository alertInboxRepository,
            AlertProcessingService alertProcessingService,
            ObjectMapper objectMapper,
            Tracer tracer,
            Propagator propagator
    ) {
        this.alertInboxRepository = alertInboxRepository;
        this.alertProcessingService = alertProcessingService;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Transactional
    public boolean processSingleNext() {
        List<AlertInboxEvent> batch = alertInboxRepository.lockNextReceivedBatch(1);
        if (batch.isEmpty()) {
            return false;
        }

        AlertInboxEvent inboxEvent = batch.get(0);
        FraudDetectedEvent event = deserialize(inboxEvent.getPayload(), inboxEvent.getEventId());
        try (TracingScope ignored = openTracingScope(inboxEvent, event)) {
            alertProcessingService.processIngestedEvent(event, inboxEvent.getOccurredAt());
        }
        return true;
    }

    private FraudDetectedEvent deserialize(String payload, String eventId) {
        try {
            return objectMapper.readValue(payload, FraudDetectedEvent.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to deserialize alert inbox payload for eventId=" + eventId, ex);
        }
    }

    private TracingScope openTracingScope(AlertInboxEvent inboxEvent, FraudDetectedEvent event) {
        String traceParent = resolveTraceParent(inboxEvent, event);
        if (traceParent == null) {
            return TracingScope.noop();
        }
        Map<String, String> headers = Map.of(
                "traceparent", traceParent,
                "baggage", trimToEmpty(inboxEvent.getBaggage())
        );
        Span span = propagator.extract(headers, Map::get)
                .name("alert_inbox_process")
                .kind(Span.Kind.CONSUMER)
                .start();
        Tracer.SpanInScope spanInScope = tracer.withSpan(span);
        return new TracingScope(span, spanInScope);
    }

    private String resolveTraceParent(AlertInboxEvent inboxEvent, FraudDetectedEvent event) {
        if (inboxEvent.getTraceParent() != null && !inboxEvent.getTraceParent().isBlank()) {
            return inboxEvent.getTraceParent();
        }
        String traceId = normalizeHex(event.traceId(), 32);
        if (traceId == null) {
            return null;
        }
        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return "00-" + traceId + "-" + spanId + "-01";
    }

    private String trimToEmpty(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
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

    private record TracingScope(Span span, Tracer.SpanInScope spanInScope) implements AutoCloseable {

        static TracingScope noop() {
            return new TracingScope(null, null);
        }

        @Override
        public void close() {
            if (spanInScope != null) {
                spanInScope.close();
            }
            if (span != null) {
                span.end();
            }
        }
    }
}
