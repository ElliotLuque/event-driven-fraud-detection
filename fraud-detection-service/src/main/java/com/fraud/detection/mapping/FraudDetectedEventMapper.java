package com.fraud.detection.mapping;

import com.fraud.detection.events.FraudDetectedEvent;
import com.fraud.detection.events.TransactionCreatedEvent;
import com.fraud.detection.rules.FraudEvaluation;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.Instant;

@Mapper(componentModel = "spring")
public interface FraudDetectedEventMapper {

    @Mapping(target = "eventId", source = "eventId")
    @Mapping(target = "occurredAt", source = "occurredAt")
    @Mapping(target = "transactionId", source = "event.transactionId")
    @Mapping(target = "userId", source = "event.userId")
    @Mapping(target = "riskScore", source = "evaluation.riskScore")
    @Mapping(target = "reasons", source = "evaluation.reasons")
    @Mapping(target = "ruleVersion", source = "ruleVersion")
    FraudDetectedEvent toFraudDetectedEvent(
            TransactionCreatedEvent event,
            FraudEvaluation evaluation,
            String eventId,
            Instant occurredAt,
            String ruleVersion
    );
}
