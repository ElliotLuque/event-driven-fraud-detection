package com.fraud.detection.service;

import com.fraud.detection.config.FraudRulesProperties;
import com.fraud.detection.events.FraudDetectedEvent;
import com.fraud.detection.events.TransactionCreatedEvent;
import com.fraud.detection.inbox.FraudInboxEvent;
import com.fraud.detection.inbox.FraudInboxRepository;
import com.fraud.detection.mapping.FraudDetectedEventMapper;
import com.fraud.detection.mapping.UserTransactionHistoryMapper;
import com.fraud.detection.model.UserTransactionHistory;
import com.fraud.detection.outbox.FraudOutboxService;
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

	private final FraudInboxRepository fraudInboxRepository;
	private final UserTransactionHistoryRepository historyRepository;
	private final FraudRulesEngine fraudRulesEngine;
	private final FraudOutboxService fraudOutboxService;
	private final FraudRulesProperties rules;
	private final UserTransactionHistoryMapper userTransactionHistoryMapper;
	private final FraudDetectedEventMapper fraudDetectedEventMapper;
	private final FraudDetectionMetrics fraudDetectionMetrics;

	public FraudDetectionService(
			FraudInboxRepository fraudInboxRepository,
			UserTransactionHistoryRepository historyRepository,
			FraudRulesEngine fraudRulesEngine,
			FraudOutboxService fraudOutboxService,
			FraudRulesProperties rules,
			UserTransactionHistoryMapper userTransactionHistoryMapper,
			FraudDetectedEventMapper fraudDetectedEventMapper,
			FraudDetectionMetrics fraudDetectionMetrics) {
		this.fraudInboxRepository = fraudInboxRepository;
		this.historyRepository = historyRepository;
		this.fraudRulesEngine = fraudRulesEngine;
		this.fraudOutboxService = fraudOutboxService;
		this.rules = rules;
		this.userTransactionHistoryMapper = userTransactionHistoryMapper;
		this.fraudDetectedEventMapper = fraudDetectedEventMapper;
		this.fraudDetectionMetrics = fraudDetectionMetrics;
	}

	@Transactional
	public void process(TransactionCreatedEvent event) {
		String traceId = resolveTraceId(event.traceId());
		long lagMs = event.occurredAt() == null ? 0
				: Math.max(0, Instant.now().toEpochMilli() - event.occurredAt().toEpochMilli());
		log.debug("fraud_event_consumed",
				kv("event", "fraud_event_consumed"),
				kv("outcome", "success"),
				kv("eventId", event.eventId()),
				kv("transactionId", event.transactionId()),
				kv("lag_ms", lagMs));

		Instant occurredAt = event.occurredAt() != null ? event.occurredAt() : Instant.now();
		boolean alreadyProcessed = ensureInboxEvent(event, occurredAt);

		if (alreadyProcessed) {
			log.warn("fraud_event_duplicate",
					kv("event", "fraud_event_duplicate"),
					kv("outcome", "duplicate"),
					kv("eventId", event.eventId()),
					kv("transactionId", event.transactionId()));
			return;
		}

		processRegisteredEvent(event, occurredAt, traceId);
	}

	@Transactional
	public void processIngestedEvent(TransactionCreatedEvent event, Instant occurredAt) {
		String traceId = resolveTraceId(event.traceId());
		Instant effectiveOccurredAt = occurredAt != null ? occurredAt : Instant.now();
		processRegisteredEvent(event, effectiveOccurredAt, traceId);
	}

	private void processRegisteredEvent(TransactionCreatedEvent event, Instant occurredAt, String traceId) {

		long recentTransactionsCount = historyRepository.countByUserIdAndOccurredAtAfter(
				event.userId(),
				occurredAt.minus(rules.getVelocityWindow()));

		Optional<UserTransactionHistory> lastTransaction = historyRepository
				.findTopByUserIdOrderByOccurredAtDesc(event.userId());

		long evaluationStartNanos = System.nanoTime();
		FraudEvaluation evaluation = fraudRulesEngine.evaluate(event, lastTransaction, recentTransactionsCount, occurredAt);
		long evaluationDurationNanos = System.nanoTime() - evaluationStartNanos;
		fraudDetectionMetrics.recordEvaluationNanos(evaluationDurationNanos);
		double evaluationDurationMs = evaluationDurationNanos / 1_000_000.0;
		log.debug("fraud_rules_evaluated",
				kv("event", "fraud_rules_evaluated"),
				kv("outcome", "success"),
				kv("eventId", event.eventId()),
				kv("transactionId", event.transactionId()),
				kv("risk_score", evaluation.riskScore()),
				kv("rules_triggered_count", evaluation.reasons().size()),
				kv("reasons", evaluation.reasons()),
				kv("duration_ms", evaluationDurationMs));

		for (String rule : evaluation.reasons()) {
			fraudDetectionMetrics.recordRuleHit(rule);
			log.debug("fraud_rule_hit",
					kv("event", "fraud_rule_hit"),
					kv("outcome", "success"),
					kv("eventId", event.eventId()),
					kv("transactionId", event.transactionId()),
					kv("rule", rule));
		}

		historyRepository.save(userTransactionHistoryMapper.toHistory(event, occurredAt));

		if (!evaluation.fraudulent()) {
			markInboxProcessed(event.eventId());
			fraudDetectionMetrics.recordDecision("clean");
			long pipelineE2eMs = Math.max(0, Instant.now().toEpochMilli() - occurredAt.toEpochMilli());
			fraudDetectionMetrics.recordPipelineE2eClean(pipelineE2eMs);
			log.info("fraud_decision_made",
					kv("event", "fraud_decision_made"),
					kv("outcome", "clean"),
					kv("eventId", event.eventId()),
					kv("transactionId", event.transactionId()),
					kv("decision", "clean"),
					kv("risk_score", evaluation.riskScore()),
					kv("pipeline_e2e_duration_ms", pipelineE2eMs),
					kv("rule_version", RULE_VERSION));
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
				kv("rule_version", RULE_VERSION));

		FraudDetectedEvent fraudDetectedEvent = fraudDetectedEventMapper.toFraudDetectedEvent(
				event,
				evaluation,
				UUID.randomUUID().toString(),
				Instant.now(),
				traceId,
				RULE_VERSION);
		try {
			fraudOutboxService.enqueue(fraudDetectedEvent);
			markInboxProcessed(event.eventId());
			log.info("fraud_event_enqueued",
					kv("event", "fraud_event_enqueued"),
					kv("outcome", "success"),
					kv("eventId", fraudDetectedEvent.eventId()),
					kv("transactionId", fraudDetectedEvent.transactionId()),
					kv("risk_score", fraudDetectedEvent.riskScore()));
		} catch (IllegalStateException ex) {
			log.error("fraud_event_enqueue_failed",
					kv("event", "fraud_event_enqueue_failed"),
					kv("outcome", "failed"),
					kv("eventId", fraudDetectedEvent.eventId()),
					kv("transactionId", fraudDetectedEvent.transactionId()),
					kv("error_code", "OUTBOX_ENQUEUE_FAILED"),
					kv("error_class", ex.getClass().getSimpleName()),
					kv("error_message", ex.getMessage()));
			throw ex;
		}
	}

	private String resolveTraceId(String eventTraceId) {
		if (eventTraceId != null && !eventTraceId.isBlank()) {
			return eventTraceId;
		}
		String mdcTraceId = MDC.get("traceId");
		if (mdcTraceId != null && !mdcTraceId.isBlank()) {
			return mdcTraceId;
		}
		return null;
	}

	private boolean ensureInboxEvent(TransactionCreatedEvent event, Instant occurredAt) {
		int inserted = fraudInboxRepository.insertReceivedIfAbsent(
				event.eventId(),
				event.transactionId(),
				event.traceId(),
				"INLINE_PROCESSING",
				occurredAt,
				Instant.now());
		if (inserted > 0) {
			return false;
		}

		FraudInboxEvent inboxEvent = fraudInboxRepository.lockByEventId(event.eventId())
				.orElseThrow(() -> new IllegalStateException("Inbox event not found after registration"));
		return inboxEvent.isProcessed();
	}

	private void markInboxProcessed(String eventId) {
		int updated = fraudInboxRepository.markProcessed(eventId, Instant.now());
		if (updated == 0) {
			throw new IllegalStateException("Inbox event not found while marking as processed");
		}
	}
}
