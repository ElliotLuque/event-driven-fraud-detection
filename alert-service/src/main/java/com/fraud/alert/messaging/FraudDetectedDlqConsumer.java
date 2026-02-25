package com.fraud.alert.messaging;

import com.fraud.alert.events.FraudDetectedEvent;
import com.fraud.alert.service.AlertProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

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

        if (event == null) {
            dlqMetrics.recordFailed();
            log.error("Received null event from fraud-detected DLQ");
            return;
        }

        try {
            alertProcessingService.process(event);
            dlqMetrics.recordReprocessed();
            log.info("Reprocessed DLQ event {} for transaction {}", event.eventId(), event.transactionId());
        } catch (RuntimeException ex) {
            dlqMetrics.recordFailed();
            log.error("Failed to reprocess DLQ event {} for transaction {}", event.eventId(), event.transactionId(), ex);
        }
    }
}
