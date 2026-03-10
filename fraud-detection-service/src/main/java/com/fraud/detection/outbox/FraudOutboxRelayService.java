package com.fraud.detection.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraud.detection.events.FraudDetectedEvent;
import com.fraud.detection.messaging.FraudEventPublisher;
import com.fraud.detection.service.FraudDetectionMetrics;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
public class FraudOutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(FraudOutboxRelayService.class);

    private final FraudOutboxRepository fraudOutboxRepository;
    private final FraudEventPublisher fraudEventPublisher;
    private final FraudDetectionMetrics fraudDetectionMetrics;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Propagator propagator;
    private final int batchSize;
    private final Duration retryDelay;
    private final Duration publishedRetention;

    public FraudOutboxRelayService(
            FraudOutboxRepository fraudOutboxRepository,
            FraudEventPublisher fraudEventPublisher,
            FraudDetectionMetrics fraudDetectionMetrics,
            ObjectMapper objectMapper,
            Tracer tracer,
            Propagator propagator,
            @Value("${app.outbox.relay-batch-size:200}") int batchSize,
            @Value("${app.outbox.retry-delay:PT2S}") Duration retryDelay,
            @Value("${app.outbox.published-retention:PT24H}") Duration publishedRetention
    ) {
        this.fraudOutboxRepository = fraudOutboxRepository;
        this.fraudEventPublisher = fraudEventPublisher;
        this.fraudDetectionMetrics = fraudDetectionMetrics;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
        this.propagator = propagator;
        this.batchSize = batchSize;
        this.retryDelay = retryDelay;
        this.publishedRetention = publishedRetention;
    }

    @Scheduled(
            fixedDelayString = "${app.outbox.relay-interval-ms:50}",
            initialDelayString = "${app.outbox.relay-initial-delay-ms:1000}"
    )
    @Transactional
    public void relayPendingEvents() {
        Instant now = Instant.now();
        List<FraudOutboxEvent> pendingBatch = fraudOutboxRepository.lockPendingBatch(now, batchSize);
        if (pendingBatch.isEmpty()) {
            return;
        }

        int publishedCount = 0;
        int failedCount = 0;
        List<PendingPublication> pendingPublications = new ArrayList<>(pendingBatch.size());

        for (FraudOutboxEvent outboxEvent : pendingBatch) {
            try {
                FraudDetectedEvent event = objectMapper.treeToValue(outboxEvent.getPayload(), FraudDetectedEvent.class);
                CompletableFuture<Void> publishFuture;
                try (TracingScope ignored = openTracingScope(outboxEvent, event)) {
                    publishFuture = fraudEventPublisher.publishAsync(
                            outboxEvent.getTopic(),
                            outboxEvent.getEventKey(),
                            event
                    );
                }
                pendingPublications.add(new PendingPublication(outboxEvent, event, publishFuture));
            } catch (Exception ex) {
                fraudDetectionMetrics.recordFraudEventPublished("failed");
                outboxEvent.markFailed(resolveErrorMessage(ex), Instant.now().plus(retryDelay));
                failedCount++;
                logPublishFailed(outboxEvent, ex);
            }
        }

        if (!pendingPublications.isEmpty()) {
            try {
                CompletableFuture.allOf(pendingPublications.stream()
                                .map(PendingPublication::publishFuture)
                                .toArray(CompletableFuture[]::new))
                        .join();
            } catch (CompletionException ignored) {
                // Individual completion handling below
            }

            for (PendingPublication publication : pendingPublications) {
                try {
                    publication.publishFuture().join();
                    fraudDetectionMetrics.recordFraudEventPublished("success");
                    publication.outboxEvent().markPublished(Instant.now());
                    log.info("fraud_event_published",
                            kv("event", "fraud_event_published"),
                            kv("outcome", "success"),
                            kv("eventId", publication.event().eventId()),
                            kv("transactionId", publication.event().transactionId()),
                            kv("risk_score", publication.event().riskScore())
                    );
                    publishedCount++;
                } catch (CompletionException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    fraudDetectionMetrics.recordFraudEventPublished("failed");
                    publication.outboxEvent().markFailed(resolveErrorMessage(cause), Instant.now().plus(retryDelay));
                    failedCount++;
                    logPublishFailed(publication.outboxEvent(), cause);
                }
            }
        }

        log.info("fraud_outbox_batch_processed",
                kv("event", "fraud_outbox_batch_processed"),
                kv("batch_size", pendingBatch.size()),
                kv("published", publishedCount),
                kv("failed", failedCount)
        );
    }

    @Scheduled(
            fixedDelayString = "${app.outbox.cleanup-interval-ms:300000}",
            initialDelayString = "${app.outbox.cleanup-initial-delay-ms:60000}"
    )
    @Transactional
    public void purgePublishedEvents() {
        Instant cutoff = Instant.now().minus(publishedRetention);
        int deleted = fraudOutboxRepository.deleteByStatusAndPublishedAtBefore(
                FraudOutboxStatus.PUBLISHED,
                cutoff
        );
        if (deleted > 0) {
            log.info("fraud_outbox_cleanup",
                    kv("event", "fraud_outbox_cleanup"),
                    kv("deleted", deleted),
                    kv("cutoff", cutoff)
            );
        }
    }

    private String resolveTraceParent(FraudOutboxEvent outboxEvent, FraudDetectedEvent event) {
        if (outboxEvent.getTraceParent() != null && !outboxEvent.getTraceParent().isBlank()) {
            return outboxEvent.getTraceParent();
        }
        String traceId = normalizeHex(event.traceId(), 32);
        if (traceId == null) {
            return null;
        }
        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return "00-" + traceId + "-" + spanId + "-01";
    }

    private TracingScope openTracingScope(FraudOutboxEvent outboxEvent, FraudDetectedEvent event) {
        String traceParent = resolveTraceParent(outboxEvent, event);
        if (traceParent == null) {
            return TracingScope.noop();
        }
        Map<String, String> headers = Map.of(
                "traceparent", traceParent,
                "baggage", trimToEmpty(outboxEvent.getBaggage())
        );
        Span span = propagator.extract(headers, Map::get)
                .name("fraud_outbox_publish")
                .kind(Span.Kind.PRODUCER)
                .start();
        Tracer.SpanInScope spanInScope = tracer.withSpan(span);
        return new TracingScope(span, spanInScope);
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

    private String resolveErrorMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message;
    }

    private void logPublishFailed(FraudOutboxEvent outboxEvent, Throwable throwable) {
        log.error("fraud_outbox_publish_failed",
                kv("event", "fraud_outbox_publish_failed"),
                kv("outcome", "failed"),
                kv("eventId", outboxEvent.getEventId()),
                kv("transactionId", outboxEvent.getEventKey()),
                kv("attempt", outboxEvent.getAttempts()),
                kv("error_class", throwable.getClass().getSimpleName()),
                kv("error_message", resolveErrorMessage(throwable))
        );
    }

    private record PendingPublication(FraudOutboxEvent outboxEvent, FraudDetectedEvent event, CompletableFuture<Void> publishFuture) {
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
