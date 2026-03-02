package com.fraud.detection.messaging;

import com.fraud.detection.events.TransactionCreatedEvent;
import com.fraud.detection.service.FraudDetectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Component
public class TransactionCreatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionCreatedConsumer.class);

    private final FraudDetectionService fraudDetectionService;

    public TransactionCreatedConsumer(FraudDetectionService fraudDetectionService) {
        this.fraudDetectionService = fraudDetectionService;
    }

    @KafkaListener(topics = "${app.kafka.topics.transactions-created}")
    public void consume(
            TransactionCreatedEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset
    ) {
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
            log.info("transaction_event_received",
                    kv("event", "transaction_event_received"),
                    kv("outcome", "success"),
                    kv("eventId", event.eventId()),
                    kv("transactionId", event.transactionId()),
                    kv("topic", topic),
                    kv("partition", partition),
                    kv("offset", offset)
            );

            fraudDetectionService.process(event);

            log.info("transaction_event_processed",
                    kv("event", "transaction_event_processed"),
                    kv("outcome", "success"),
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
}
