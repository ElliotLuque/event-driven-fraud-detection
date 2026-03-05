package com.fraud.transaction.service;

import com.fraud.transaction.api.TransactionRequest;
import com.fraud.transaction.api.TransactionResponse;
import com.fraud.transaction.domain.Transaction;
import com.fraud.transaction.events.TransactionCreatedEvent;
import com.fraud.transaction.mapping.TransactionMapper;
import com.fraud.transaction.outbox.TransactionOutboxService;
import com.fraud.transaction.repository.TransactionRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository transactionRepository;
    private final TransactionOutboxService transactionOutboxService;
    private final TransactionMapper transactionMapper;
    private final TransactionMetrics transactionMetrics;
    private final Tracer tracer;

    public TransactionService(
            TransactionRepository transactionRepository,
            TransactionOutboxService transactionOutboxService,
            TransactionMapper transactionMapper,
            TransactionMetrics transactionMetrics,
            Tracer tracer
    ) {
        this.transactionRepository = transactionRepository;
        this.transactionOutboxService = transactionOutboxService;
        this.transactionMapper = transactionMapper;
        this.transactionMetrics = transactionMetrics;
        this.tracer = tracer;
    }

    @Transactional
    public TransactionResponse createTransaction(TransactionRequest request) {
        return createTransaction(request, "api");
    }

    @Transactional
    public TransactionResponse createTransaction(TransactionRequest request, String channel) {
        String transactionId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String normalizedChannel = normalizeChannel(channel);
        String traceId = resolveOrGenerateTraceId();

        transactionMetrics.recordTransactionReceived(normalizedChannel);
        log.info("transaction_received",
                kv("event", "transaction_received"),
                kv("outcome", "success"),
                kv("channel", normalizedChannel),
                kv("transactionId", transactionId),
                kv("user_hash", Integer.toHexString(request.userId().hashCode())),
                kv("amount", request.amount()),
                kv("currency", request.currency()),
                kv("country", request.country()),
                kv("merchantId", request.merchantId())
        );

        long persistedStartNanos = System.nanoTime();
        Transaction transaction = transactionMapper.toTransaction(request, transactionId, now);
        transactionRepository.save(transaction);
        long persistDurationMs = (System.nanoTime() - persistedStartNanos) / 1_000_000;
        transactionMetrics.recordTransactionPersisted(persistDurationMs);
        log.info("transaction_persisted",
                kv("event", "transaction_persisted"),
                kv("outcome", "success"),
                kv("transactionId", transactionId),
                kv("duration_ms", persistDurationMs)
        );

        TransactionCreatedEvent event = transactionMapper.toCreatedEvent(
                transaction,
                UUID.randomUUID().toString(),
                now,
                traceId
        );
        long enqueueStartNanos = System.nanoTime();
        try {
            transactionOutboxService.enqueue(event);
            long enqueueDurationMs = (System.nanoTime() - enqueueStartNanos) / 1_000_000;
            transactionMetrics.recordTransactionEventEnqueued("success", enqueueDurationMs);
            log.info("transaction_event_enqueued",
                    kv("event", "transaction_event_enqueued"),
                    kv("outcome", "success"),
                    kv("transactionId", transactionId),
                    kv("eventId", event.eventId()),
                    kv("duration_ms", enqueueDurationMs)
            );
        } catch (IllegalStateException ex) {
            long enqueueDurationMs = (System.nanoTime() - enqueueStartNanos) / 1_000_000;
            transactionMetrics.recordTransactionEventEnqueued("failed", enqueueDurationMs);
            log.error("transaction_event_enqueue_failed",
                    kv("event", "transaction_event_enqueue_failed"),
                    kv("outcome", "failed"),
                    kv("transactionId", transactionId),
                    kv("eventId", event.eventId()),
                    kv("duration_ms", enqueueDurationMs),
                    kv("error_code", "OUTBOX_ENQUEUE_FAILED"),
                    kv("error_class", ex.getClass().getSimpleName()),
                    kv("error_message", ex.getMessage())
            );
            throw ex;
        }

        return transactionMapper.toResponse(transaction);
    }

    private String normalizeChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return "unknown";
        }
        return channel.toLowerCase(Locale.ROOT);
    }

    private String resolveOrGenerateTraceId() {
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null && currentSpan.context() != null) {
            String traceId = normalizeHex(currentSpan.context().traceId(), 32);
            if (traceId != null) {
                return traceId;
            }
        }
        String traceId = MDC.get("traceId");
        String normalizedMdcTraceId = normalizeHex(traceId, 32);
        if (normalizedMdcTraceId != null) {
            return normalizedMdcTraceId;
        }
        return UUID.randomUUID().toString().replace("-", "");
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
}
