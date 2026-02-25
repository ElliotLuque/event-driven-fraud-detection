package com.fraud.detection.service;

import com.fraud.detection.config.FraudRulesProperties;
import com.fraud.detection.events.FraudDetectedEvent;
import com.fraud.detection.events.TransactionCreatedEvent;
import com.fraud.detection.mapping.FraudDetectedEventMapper;
import com.fraud.detection.mapping.UserTransactionHistoryMapper;
import com.fraud.detection.messaging.FraudEventPublisher;
import com.fraud.detection.model.ProcessedEvent;
import com.fraud.detection.model.UserTransactionHistory;
import com.fraud.detection.repository.ProcessedEventRepository;
import com.fraud.detection.repository.UserTransactionHistoryRepository;
import com.fraud.detection.rules.FraudEvaluation;
import com.fraud.detection.rules.FraudRulesEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class FraudDetectionService {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionService.class);
    private static final String RULE_VERSION = "v1.0.0";

    private final ProcessedEventRepository processedEventRepository;
    private final UserTransactionHistoryRepository historyRepository;
    private final FraudRulesEngine fraudRulesEngine;
    private final FraudEventPublisher fraudEventPublisher;
    private final FraudRulesProperties rules;
    private final UserTransactionHistoryMapper userTransactionHistoryMapper;
    private final FraudDetectedEventMapper fraudDetectedEventMapper;

    public FraudDetectionService(
            ProcessedEventRepository processedEventRepository,
            UserTransactionHistoryRepository historyRepository,
            FraudRulesEngine fraudRulesEngine,
            FraudEventPublisher fraudEventPublisher,
            FraudRulesProperties rules,
            UserTransactionHistoryMapper userTransactionHistoryMapper,
            FraudDetectedEventMapper fraudDetectedEventMapper
    ) {
        this.processedEventRepository = processedEventRepository;
        this.historyRepository = historyRepository;
        this.fraudRulesEngine = fraudRulesEngine;
        this.fraudEventPublisher = fraudEventPublisher;
        this.rules = rules;
        this.userTransactionHistoryMapper = userTransactionHistoryMapper;
        this.fraudDetectedEventMapper = fraudDetectedEventMapper;
    }

    @Transactional
    public void process(TransactionCreatedEvent event) {
        if (processedEventRepository.existsById(event.eventId())) {
            log.info("Event {} already processed, skipping", event.eventId());
            return;
        }

        Instant occurredAt = event.occurredAt() != null ? event.occurredAt() : Instant.now();

        long recentTransactionsCount = historyRepository.countByUserIdAndOccurredAtAfter(
                event.userId(),
                occurredAt.minus(rules.getVelocityWindow())
        );

        Optional<UserTransactionHistory> lastTransaction = historyRepository.findTopByUserIdOrderByOccurredAtDesc(event.userId());

        FraudEvaluation evaluation = fraudRulesEngine.evaluate(event, lastTransaction, recentTransactionsCount, occurredAt);

        historyRepository.save(userTransactionHistoryMapper.toHistory(event, occurredAt));
        processedEventRepository.save(new ProcessedEvent(event.eventId(), occurredAt));

        if (!evaluation.fraudulent()) {
            return;
        }

        FraudDetectedEvent fraudDetectedEvent = fraudDetectedEventMapper.toFraudDetectedEvent(
                event,
                evaluation,
                UUID.randomUUID().toString(),
                Instant.now(),
                RULE_VERSION
        );
        fraudEventPublisher.publish(fraudDetectedEvent);
        log.warn("Fraud detected for transaction {} with reasons {}", event.transactionId(), evaluation.reasons());
    }
}
