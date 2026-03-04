package com.fraud.transaction.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraud.transaction.events.TransactionCreatedEvent;
import com.fraud.transaction.messaging.TransactionEventPublisher;
import com.fraud.transaction.service.TransactionMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
public class TransactionOutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(TransactionOutboxRelayService.class);

    private final TransactionOutboxRepository transactionOutboxRepository;
    private final TransactionEventPublisher transactionEventPublisher;
    private final TransactionMetrics transactionMetrics;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final Duration retryDelay;
    private final Duration publishedRetention;

    public TransactionOutboxRelayService(
            TransactionOutboxRepository transactionOutboxRepository,
            TransactionEventPublisher transactionEventPublisher,
            TransactionMetrics transactionMetrics,
            ObjectMapper objectMapper,
            @Value("${app.outbox.relay-batch-size:200}") int batchSize,
            @Value("${app.outbox.retry-delay:PT2S}") Duration retryDelay,
            @Value("${app.outbox.published-retention:PT24H}") Duration publishedRetention
    ) {
        this.transactionOutboxRepository = transactionOutboxRepository;
        this.transactionEventPublisher = transactionEventPublisher;
        this.transactionMetrics = transactionMetrics;
        this.objectMapper = objectMapper;
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
        for (TransactionOutboxEvent outboxEvent : pendingBatch) {
            long publishStartedNanos = System.nanoTime();
            try {
                TransactionCreatedEvent event = objectMapper.readValue(outboxEvent.getPayload(), TransactionCreatedEvent.class);
                transactionEventPublisher.publish(outboxEvent.getTopic(), outboxEvent.getEventKey(), event);

                long publishDurationMs = (System.nanoTime() - publishStartedNanos) / 1_000_000;
                transactionMetrics.recordTransactionEventPublished("success", publishDurationMs);
                outboxEvent.markPublished(Instant.now());
                publishedCount++;
            } catch (Exception ex) {
                long publishDurationMs = (System.nanoTime() - publishStartedNanos) / 1_000_000;
                transactionMetrics.recordTransactionEventPublished("failed", publishDurationMs);
                outboxEvent.markFailed(ex.getMessage(), Instant.now().plus(retryDelay));
                failedCount++;
                log.error("transaction_outbox_publish_failed",
                        kv("event", "transaction_outbox_publish_failed"),
                        kv("outcome", "failed"),
                        kv("eventId", outboxEvent.getEventId()),
                        kv("transactionId", outboxEvent.getEventKey()),
                        kv("attempt", outboxEvent.getAttempts()),
                        kv("error_class", ex.getClass().getSimpleName()),
                        kv("error_message", ex.getMessage())
                );
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
}
