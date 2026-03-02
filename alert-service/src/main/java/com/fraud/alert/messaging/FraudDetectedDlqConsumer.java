package com.fraud.alert.messaging;

import com.fraud.alert.events.FraudDetectedEvent;
import com.fraud.alert.service.AlertProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Component
public class FraudDetectedDlqConsumer {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectedDlqConsumer.class);

    private final AlertProcessingService alertProcessingService;
    private final DlqMetrics dlqMetrics;

    public FraudDetectedDlqConsumer(AlertProcessingService alertProcessingService, DlqMetrics dlqMetrics) {
        this.alertProcessingService = alertProcessingService;
        this.dlqMetrics = dlqMetrics;
    }

    @KafkaListener(topics = "${app.kafka.topics.fraud-detected}.dlq")
    public void consume(FraudDetectedEvent event) {
        dlqMetrics.recordReceived();

        String previousTraceId = MDC.get("traceId");
        boolean injectedTraceId = false;
        if ((previousTraceId == null || previousTraceId.isBlank()) && event != null && event.traceId() != null && !event.traceId().isBlank()) {
            MDC.put("traceId", event.traceId());
            injectedTraceId = true;
        }

        if (event == null) {
            dlqMetrics.recordFailed();
            log.error("fraud_detected_dlq_event_received",
                    kv("event", "fraud_detected_dlq_event_received"),
                    kv("outcome", "failed"),
                    kv("error_code", "EMPTY_EVENT_PAYLOAD")
            );
            if (injectedTraceId) {
                MDC.remove("traceId");
            }
            return;
        }

        try {
            alertProcessingService.process(event);
            dlqMetrics.recordReprocessed();
            log.info("fraud_detected_dlq_event_reprocessed",
                    kv("event", "fraud_detected_dlq_event_reprocessed"),
                    kv("outcome", "success"),
                    kv("eventId", event.eventId()),
                    kv("transactionId", event.transactionId())
            );
        } catch (RuntimeException ex) {
            dlqMetrics.recordFailed();
            log.error("fraud_detected_dlq_event_reprocess_failed",
                    kv("event", "fraud_detected_dlq_event_reprocess_failed"),
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
