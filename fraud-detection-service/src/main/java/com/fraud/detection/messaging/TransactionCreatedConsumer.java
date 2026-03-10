package com.fraud.detection.messaging;

import com.fraud.detection.events.TransactionCreatedEvent;
import com.fraud.detection.service.FraudInboxIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.List;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Component
public class TransactionCreatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionCreatedConsumer.class);

    private final FraudInboxIngestionService fraudInboxIngestionService;

    public TransactionCreatedConsumer(FraudInboxIngestionService fraudInboxIngestionService) {
        this.fraudInboxIngestionService = fraudInboxIngestionService;
    }

    @KafkaListener(topics = "${app.kafka.topics.transactions-created}")
    public void consume(
            List<TransactionCreatedEvent> events,
            @Header(KafkaHeaders.RECEIVED_TOPIC) List<String> topics,
            @Header(KafkaHeaders.RECEIVED_PARTITION) List<Integer> partitions,
            @Header(KafkaHeaders.OFFSET) List<Long> offsets
    ) {
        if (events == null || events.isEmpty()) {
            return;
        }

        for (int i = 0; i < events.size(); i++) {
            TransactionCreatedEvent event = events.get(i);
            String topic = valueAt(topics, i, "unknown");
            int partition = valueAt(partitions, i, -1);
            long offset = valueAt(offsets, i, -1L);
            consumeSingle(event, topic, partition, offset);
        }
    }

    private void consumeSingle(TransactionCreatedEvent event, String topic, int partition, long offset) {
        String previousTraceId = MDC.get("traceId");
        boolean injectedTraceId = false;
        if ((previousTraceId == null || previousTraceId.isBlank()) && event != null && event.traceId() != null && !event.traceId().isBlank()) {
            MDC.put("traceId", event.traceId());
            injectedTraceId = true;
        }

        if (event == null) {
            log.warn("transaction_event_received",
                    kv("event", "transaction_event_received"),
                    kv("outcome", "failed"),
                    kv("topic", topic),
                    kv("partition", partition),
                    kv("offset", offset),
                    kv("error_code", "EMPTY_EVENT_PAYLOAD")
            );
            if (injectedTraceId) {
                MDC.remove("traceId");
            }
            return;
        }

        try {
            log.debug("transaction_event_received",
                    kv("event", "transaction_event_received"),
                    kv("outcome", "success"),
                    kv("eventId", event.eventId()),
                    kv("transactionId", event.transactionId()),
                    kv("topic", topic),
                    kv("partition", partition),
                    kv("offset", offset)
            );

            boolean accepted = fraudInboxIngestionService.ingest(event);

            log.debug("transaction_event_enqueued",
                    kv("event", "transaction_event_enqueued"),
                    kv("outcome", accepted ? "success" : "duplicate"),
                    kv("eventId", event.eventId()),
                    kv("transactionId", event.transactionId()),
                    kv("topic", topic),
                    kv("partition", partition),
                    kv("offset", offset)
            );
        } finally {
            if (injectedTraceId) {
                MDC.remove("traceId");
            }
        }
    }

    private static <T> T valueAt(List<T> values, int index, T fallback) {
        if (values == null || index < 0 || index >= values.size()) {
            return fallback;
        }
        T value = values.get(index);
        return value != null ? value : fallback;
    }
}
