package com.fraud.alert.messaging;

import com.fraud.alert.events.FraudDetectedEvent;
import com.fraud.alert.service.AlertInboxIngestionService;
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
public class FraudDetectedConsumer {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectedConsumer.class);

    private final AlertInboxIngestionService alertInboxIngestionService;

    public FraudDetectedConsumer(AlertInboxIngestionService alertInboxIngestionService) {
        this.alertInboxIngestionService = alertInboxIngestionService;
    }

    @KafkaListener(topics = "${app.kafka.topics.fraud-detected}")
    public void consume(
            List<FraudDetectedEvent> events,
            @Header(KafkaHeaders.RECEIVED_TOPIC) List<String> topics,
            @Header(KafkaHeaders.RECEIVED_PARTITION) List<Integer> partitions,
            @Header(KafkaHeaders.OFFSET) List<Long> offsets
    ) {
        if (events == null || events.isEmpty()) {
            return;
        }

        for (int i = 0; i < events.size(); i++) {
            FraudDetectedEvent event = events.get(i);
            String topic = valueAt(topics, i, "unknown");
            int partition = valueAt(partitions, i, -1);
            long offset = valueAt(offsets, i, -1L);
            consumeSingle(event, topic, partition, offset);
        }
    }

    private void consumeSingle(FraudDetectedEvent event, String topic, int partition, long offset) {
        String previousTraceId = MDC.get("traceId");
        boolean injectedTraceId = false;
        if ((previousTraceId == null || previousTraceId.isBlank()) && event != null && event.traceId() != null && !event.traceId().isBlank()) {
            MDC.put("traceId", event.traceId());
            injectedTraceId = true;
        }

        if (event == null) {
            log.warn("fraud_event_received",
                    kv("event", "fraud_event_received"),
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
            log.info("fraud_event_received",
                    kv("event", "fraud_event_received"),
                    kv("outcome", "success"),
                    kv("eventId", event.eventId()),
                    kv("transactionId", event.transactionId()),
                    kv("topic", topic),
                    kv("partition", partition),
                    kv("offset", offset)
            );

            boolean accepted = alertInboxIngestionService.ingest(event);

            log.info("fraud_event_enqueued",
                    kv("event", "fraud_event_enqueued"),
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
