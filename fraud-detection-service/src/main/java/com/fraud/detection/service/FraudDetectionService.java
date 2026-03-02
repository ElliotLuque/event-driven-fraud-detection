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
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

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
    private final FraudDetectionMetrics fraudDetectionMetrics;

    public FraudDetectionService(
            ProcessedEventRepository processedEventRepository,
            UserTransactionHistoryRepository historyRepository,
            FraudRulesEngine fraudRulesEngine,
            FraudEventPublisher fraudEventPublisher,
            FraudRulesProperties rules,
            UserTransactionHistoryMapper userTransactionHistoryMapper,
            FraudDetectedEventMapper fraudDetectedEventMapper,
            FraudDetectionMetrics fraudDetectionMetrics
    ) {
        this.processedEventRepository = processedEventRepository;
        this.historyRepository = historyRepository;
        this.fraudRulesEngine = fraudRulesEngine;
        this.fraudEventPublisher = fraudEventPublisher;
        this.rules = rules;
        this.userTransactionHistoryMapper = userTransactionHistoryMapper;
        this.fraudDetectedEventMapper = fraudDetectedEventMapper;
        this.fraudDetectionMetrics = fraudDetectionMetrics;
    }

    @Transactional
    public void process(TransactionCreatedEvent event) {
        fraudDetectionMetrics.recordEventConsumed();
        String traceId = resolveTraceId(event.traceId());
        long lagMs = event.occurredAt() == null ? 0 : Math.max(0, Instant.now().toEpochMilli() - event.occurredAt().toEpochMilli());
        log.info("fraud_event_consumed",
                kv("event", "fraud_event_consumed"),
                kv("outcome", "success"),
                kv("eventId", event.eventId()),
                kv("transactionId", event.transactionId()),
                kv("lag_ms", lagMs)
        );

        if (processedEventRepository.existsById(event.eventId())) {
            log.info("fraud_event_duplicate",
                    kv("event", "fraud_event_duplicate"),
                    kv("outcome", "duplicate"),
                    kv("eventId", event.eventId()),
                    kv("transactionId", event.transactionId())
            );
            return;
        }

        Instant occurredAt = event.occurredAt() != null ? event.occurredAt() : Instant.now();

        long recentTransactionsCount = historyRepository.countByUserIdAndOccurredAtAfter(
                event.userId(),
                occurredAt.minus(rules.getVelocityWindow())
        );

        Optional<UserTransactionHistory> lastTransaction = historyRepository.findTopByUserIdOrderByOccurredAtDesc(event.userId());

        long evaluationStartNanos = System.nanoTime();
        FraudEvaluation evaluation = fraudRulesEngine.evaluate(event, lastTransaction, recentTransactionsCount, occurredAt);
        long evaluationDurationNanos = System.nanoTime() - evaluationStartNanos;
        fraudDetectionMetrics.recordEvaluationNanos(evaluationDurationNanos);
        double evaluationDurationMs = evaluationDurationNanos / 1_000_000.0;
        log.info("fraud_rules_evaluated",
                kv("event", "fraud_rules_evaluated"),
                kv("outcome", "success"),
                kv("eventId", event.eventId()),
                kv("transactionId", event.transactionId()),
                kv("risk_score", evaluation.riskScore()),
                kv("rules_triggered_count", evaluation.reasons().size()),
                kv("reasons", evaluation.reasons()),
                kv("duration_ms", evaluationDurationMs)
        );

        for (String rule : evaluation.reasons()) {
            fraudDetectionMetrics.recordRuleHit(rule);
            log.info("fraud_rule_hit",
                    kv("event", "fraud_rule_hit"),
                    kv("outcome", "success"),
                    kv("eventId", event.eventId()),
                    kv("transactionId", event.transactionId()),
                    kv("rule", rule)
            );
        }

        historyRepository.save(userTransactionHistoryMapper.toHistory(event, occurredAt));
        processedEventRepository.save(new ProcessedEvent(event.eventId(), occurredAt));

        if (!evaluation.fraudulent()) {
            fraudDetectionMetrics.recordDecision("clean");
            log.info("fraud_decision_made",
                    kv("event", "fraud_decision_made"),
                    kv("outcome", "clean"),
                    kv("eventId", event.eventId()),
                    kv("transactionId", event.transactionId()),
                    kv("decision", "clean"),
                    kv("risk_score", evaluation.riskScore()),
                    kv("rule_version", RULE_VERSION)
            );
            return;
        }

        fraudDetectionMetrics.recordDecision("fraud");
        log.warn("fraud_decision_made",
                kv("event", "fraud_decision_made"),
                kv("outcome", "fraud"),
                kv("eventId", event.eventId()),
                kv("transactionId", event.transactionId()),
                kv("decision", "fraud"),
                kv("risk_score", evaluation.riskScore()),
                kv("reasons", evaluation.reasons()),
                kv("rule_version", RULE_VERSION)
        );

        FraudDetectedEvent fraudDetectedEvent = fraudDetectedEventMapper.toFraudDetectedEvent(
                event,
                evaluation,
                UUID.randomUUID().toString(),
                Instant.now(),
                traceId,
                RULE_VERSION
        );
        try {
            fraudEventPublisher.publish(fraudDetectedEvent);
            fraudDetectionMetrics.recordFraudEventPublished("success");
            log.info("fraud_event_published",
                    kv("event", "fraud_event_published"),
                    kv("outcome", "success"),
                    kv("eventId", fraudDetectedEvent.eventId()),
                    kv("transactionId", fraudDetectedEvent.transactionId()),
                    kv("risk_score", fraudDetectedEvent.riskScore())
            );
        } catch (IllegalStateException ex) {
            fraudDetectionMetrics.recordFraudEventPublished("failed");
            log.error("fraud_event_publish_failed",
                    kv("event", "fraud_event_publish_failed"),
                    kv("outcome", "failed"),
                    kv("eventId", fraudDetectedEvent.eventId()),
                    kv("transactionId", fraudDetectedEvent.transactionId()),
                    kv("error_code", "KAFKA_PUBLISH_FAILED"),
                    kv("error_class", ex.getClass().getSimpleName()),
                    ex
            );
            throw ex;
        }
    }

    private String resolveTraceId(String eventTraceId) {
        String mdcTraceId = MDC.get("traceId");
        if (mdcTraceId != null && !mdcTraceId.isBlank()) {
            return mdcTraceId;
        }
        return eventTraceId;
    }
}
