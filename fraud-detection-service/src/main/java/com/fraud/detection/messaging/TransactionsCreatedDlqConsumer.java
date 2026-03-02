package com.fraud.detection.messaging;

import com.fraud.detection.events.TransactionCreatedEvent;
import com.fraud.detection.service.FraudDetectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Component
public class TransactionsCreatedDlqConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionsCreatedDlqConsumer.class);

    private final FraudDetectionService fraudDetectionService;
    private final DlqMetrics dlqMetrics;

    public TransactionsCreatedDlqConsumer(FraudDetectionService fraudDetectionService, DlqMetrics dlqMetrics) {
        this.fraudDetectionService = fraudDetectionService;
        this.dlqMetrics = dlqMetrics;
    }

    @KafkaListener(topics = "${app.kafka.topics.transactions-created}.dlq")
    public void consume(TransactionCreatedEvent event) {
        dlqMetrics.recordReceived();

        String previousTraceId = MDC.get("traceId");
        boolean injectedTraceId = false;
        if ((previousTraceId == null || previousTraceId.isBlank()) && event != null && event.traceId() != null && !event.traceId().isBlank()) {
            MDC.put("traceId", event.traceId());
            injectedTraceId = true;
        }

        if (event == null) {
            dlqMetrics.recordFailed();
            log.error("transactions_created_dlq_event_received",
                    kv("event", "transactions_created_dlq_event_received"),
                    kv("outcome", "failed"),
                    kv("error_code", "EMPTY_EVENT_PAYLOAD")
            );
            if (injectedTraceId) {
                MDC.remove("traceId");
            }
            return;
        }

        try {
            fraudDetectionService.process(event);
            dlqMetrics.recordReprocessed();
            log.info("transactions_created_dlq_event_reprocessed",
                    kv("event", "transactions_created_dlq_event_reprocessed"),
                    kv("outcome", "success"),
                    kv("eventId", event.eventId()),
                    kv("transactionId", event.transactionId())
            );
        } catch (RuntimeException ex) {
            dlqMetrics.recordFailed();
            log.error("transactions_created_dlq_event_reprocess_failed",
                    kv("event", "transactions_created_dlq_event_reprocess_failed"),
                    kv("outcome", "failed"),
                    kv("eventId", event.eventId()),
                    kv("transactionId", event.transactionId()),
                    kv("error_class", ex.getClass().getSimpleName()),
                    ex
            );
        } finally {
            if (injectedTraceId) {
                MDC.remove("traceId");
            }
        }
    }
}
