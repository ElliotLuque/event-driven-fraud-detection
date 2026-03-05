package com.fraud.transaction.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraud.transaction.events.TransactionCreatedEvent;
import com.fraud.transaction.messaging.TransactionEventPublisher;
import com.fraud.transaction.service.TransactionMetrics;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
public class TransactionOutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(TransactionOutboxRelayService.class);

    private final TransactionOutboxRepository transactionOutboxRepository;
    private final TransactionEventPublisher transactionEventPublisher;
    private final TransactionMetrics transactionMetrics;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Propagator propagator;
    private final int batchSize;
    private final Duration retryDelay;
    private final Duration publishedRetention;

    public TransactionOutboxRelayService(
            TransactionOutboxRepository transactionOutboxRepository,
            TransactionEventPublisher transactionEventPublisher,
            TransactionMetrics transactionMetrics,
            ObjectMapper objectMapper,
            Tracer tracer,
            Propagator propagator,
            @Value("${app.outbox.relay-batch-size:200}") int batchSize,
            @Value("${app.outbox.retry-delay:PT2S}") Duration retryDelay,
            @Value("${app.outbox.published-retention:PT24H}") Duration publishedRetention
    ) {
        this.transactionOutboxRepository = transactionOutboxRepository;
        this.transactionEventPublisher = transactionEventPublisher;
        this.transactionMetrics = transactionMetrics;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
        this.propagator = propagator;
        this.batchSize = batchSize;
        this.retryDelay = retryDelay;
        this.publishedRetention = publishedRetention;
    }

    @Scheduled(
            fixedDelayString = "${app.outbox.relay-interval-ms:200}",
            initialDelayString = "${app.outbox.relay-initial-delay-ms:1000}"
    )
    @Transactional
    public void relayPendingEvents() {
        Instant now = Instant.now();
        List<TransactionOutboxEvent> pendingBatch = transactionOutboxRepository.lockPendingBatch(now, batchSize);
        if (pendingBatch.isEmpty()) {
            return;
        }

        int publishedCount = 0;
        int failedCount = 0;
        List<PendingPublication> pendingPublications = new ArrayList<>(pendingBatch.size());

        for (TransactionOutboxEvent outboxEvent : pendingBatch) {
            long publishStartedNanos = System.nanoTime();
            try {
                TransactionCreatedEvent event = objectMapper.treeToValue(outboxEvent.getPayload(), TransactionCreatedEvent.class);
                CompletableFuture<Void> publishFuture;
                try (TracingScope ignored = openTracingScope(outboxEvent, event)) {
                    publishFuture = transactionEventPublisher.publishAsync(
                            outboxEvent.getTopic(),
                            outboxEvent.getEventKey(),
                            event
                    );
                }
                pendingPublications.add(new PendingPublication(outboxEvent, publishFuture, publishStartedNanos));
            } catch (Exception ex) {
                long publishDurationMs = (System.nanoTime() - publishStartedNanos) / 1_000_000;
                transactionMetrics.recordTransactionEventPublished("failed", publishDurationMs);
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
                long publishDurationMs = (System.nanoTime() - publication.publishStartedNanos()) / 1_000_000;
                try {
                    publication.publishFuture().join();
                    transactionMetrics.recordTransactionEventPublished("success", publishDurationMs);
                    publication.outboxEvent().markPublished(Instant.now());
                    publishedCount++;
                } catch (CompletionException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    transactionMetrics.recordTransactionEventPublished("failed", publishDurationMs);
                    publication.outboxEvent().markFailed(resolveErrorMessage(cause), Instant.now().plus(retryDelay));
                    failedCount++;
                    logPublishFailed(publication.outboxEvent(), cause);
                }
            }
        }

        log.info("transaction_outbox_batch_processed",
                kv("event", "transaction_outbox_batch_processed"),
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
        int deleted = transactionOutboxRepository.deleteByStatusAndPublishedAtBefore(
                TransactionOutboxStatus.PUBLISHED,
                cutoff
        );
        if (deleted > 0) {
            log.info("transaction_outbox_cleanup",
                    kv("event", "transaction_outbox_cleanup"),
                    kv("deleted", deleted),
                    kv("cutoff", cutoff)
            );
        }
    }

    private String resolveTraceParent(TransactionOutboxEvent outboxEvent, TransactionCreatedEvent event) {
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

    private TracingScope openTracingScope(TransactionOutboxEvent outboxEvent, TransactionCreatedEvent event) {
        String traceParent = resolveTraceParent(outboxEvent, event);
        if (traceParent == null) {
            return TracingScope.noop();
        }
        Map<String, String> headers = Map.of(
                "traceparent", traceParent,
                "baggage", trimToEmpty(outboxEvent.getBaggage())
        );
        Span span = propagator.extract(headers, Map::get)
                .name("transaction_outbox_publish")
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

    private void logPublishFailed(TransactionOutboxEvent outboxEvent, Throwable throwable) {
        log.error("transaction_outbox_publish_failed",
                kv("event", "transaction_outbox_publish_failed"),
                kv("outcome", "failed"),
                kv("eventId", outboxEvent.getEventId()),
                kv("transactionId", outboxEvent.getEventKey()),
                kv("attempt", outboxEvent.getAttempts()),
                kv("error_class", throwable.getClass().getSimpleName()),
                kv("error_message", resolveErrorMessage(throwable))
        );
    }

    private record PendingPublication(
            TransactionOutboxEvent outboxEvent,
            CompletableFuture<Void> publishFuture,
            long publishStartedNanos
    ) {
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
