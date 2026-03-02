package com.fraud.transaction.service;

import com.fraud.transaction.api.TransactionRequest;
import com.fraud.transaction.api.TransactionResponse;
import com.fraud.transaction.domain.Transaction;
import com.fraud.transaction.events.TransactionCreatedEvent;
import com.fraud.transaction.mapping.TransactionMapper;
import com.fraud.transaction.messaging.TransactionEventPublisher;
import com.fraud.transaction.repository.TransactionRepository;
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
    private final TransactionEventPublisher transactionEventPublisher;
    private final TransactionMapper transactionMapper;
    private final TransactionMetrics transactionMetrics;

    public TransactionService(
            TransactionRepository transactionRepository,
            TransactionEventPublisher transactionEventPublisher,
            TransactionMapper transactionMapper,
            TransactionMetrics transactionMetrics
    ) {
        this.transactionRepository = transactionRepository;
        this.transactionEventPublisher = transactionEventPublisher;
        this.transactionMapper = transactionMapper;
        this.transactionMetrics = transactionMetrics;
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
        String traceId = MDC.get("traceId");

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
        long publishStartNanos = System.nanoTime();
        try {
            transactionEventPublisher.publish(event);
            long publishDurationMs = (System.nanoTime() - publishStartNanos) / 1_000_000;
            transactionMetrics.recordTransactionEventPublished("success", publishDurationMs);
            log.info("transaction_event_published",
                    kv("event", "transaction_event_published"),
                    kv("outcome", "success"),
                    kv("transactionId", transactionId),
                    kv("eventId", event.eventId()),
                    kv("duration_ms", publishDurationMs)
            );
        } catch (IllegalStateException ex) {
            long publishDurationMs = (System.nanoTime() - publishStartNanos) / 1_000_000;
            transactionMetrics.recordTransactionEventPublished("failed", publishDurationMs);
            log.error("transaction_event_publish_failed",
                    kv("event", "transaction_event_publish_failed"),
                    kv("outcome", "failed"),
                    kv("transactionId", transactionId),
                    kv("eventId", event.eventId()),
                    kv("duration_ms", publishDurationMs),
                    kv("error_code", "KAFKA_PUBLISH_FAILED"),
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
}
