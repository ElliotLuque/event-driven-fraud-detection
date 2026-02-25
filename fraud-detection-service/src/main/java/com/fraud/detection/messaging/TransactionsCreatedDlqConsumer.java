package com.fraud.detection.messaging;

import com.fraud.detection.events.TransactionCreatedEvent;
import com.fraud.detection.service.FraudDetectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

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

        if (event == null) {
            dlqMetrics.recordFailed();
            log.error("Received null event from transactions-created DLQ");
            return;
        }

        try {
            fraudDetectionService.process(event);
            dlqMetrics.recordReprocessed();
            log.info("Reprocessed DLQ event {} for transaction {}", event.eventId(), event.transactionId());
        } catch (RuntimeException ex) {
            dlqMetrics.recordFailed();
            log.error("Failed to reprocess DLQ event {} for transaction {}", event.eventId(), event.transactionId(), ex);
        }
    }
}
