package com.fraud.alert.messaging;

import com.fraud.alert.events.FraudDetectedEvent;
import com.fraud.alert.service.AlertProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Component
public class FraudDetectedConsumer {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectedConsumer.class);

    private final AlertProcessingService alertProcessingService;

    public FraudDetectedConsumer(AlertProcessingService alertProcessingService) {
        this.alertProcessingService = alertProcessingService;
    }

    @KafkaListener(topics = "${app.kafka.topics.fraud-detected}")
    public void consume(
            FraudDetectedEvent event,
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

            alertProcessingService.process(event);

            log.info("fraud_event_processed",
                    kv("event", "fraud_event_processed"),
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
