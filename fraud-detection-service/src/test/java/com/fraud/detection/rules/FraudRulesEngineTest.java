package com.fraud.detection.rules;

import com.fraud.detection.config.FraudRulesProperties;
import com.fraud.detection.events.TransactionCreatedEvent;
import com.fraud.detection.model.PaymentMethod;
import com.fraud.detection.model.UserTransactionHistory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FraudRulesEngineTest {

    @Test
    void highAmountAndHighRiskMerchantShouldBeFraudulent() {
        FraudRulesEngine engine = new FraudRulesEngine(defaultRules());
        TransactionCreatedEvent event = buildEvent("15000.00", "MRC-999", "AR");

        FraudEvaluation evaluation = engine.evaluate(event, Optional.empty(), 0, Instant.now());

        assertTrue(evaluation.fraudulent());
        assertTrue(evaluation.reasons().contains("HIGH_AMOUNT"));
        assertTrue(evaluation.reasons().contains("HIGH_RISK_MERCHANT"));
        assertEquals(70, evaluation.riskScore());
    }

    @Test
    void velocityAndCountryChangeShouldIncreaseRiskScore() {
        FraudRulesEngine engine = new FraudRulesEngine(defaultRules());
        Instant now = Instant.now();

        UserTransactionHistory last = new UserTransactionHistory(
                "old-transaction",
                "user-1",
                new BigDecimal("45.00"),
                "USD",
                "MRC-100",
                "US",
                now.minus(Duration.ofMinutes(5))
        );

        TransactionCreatedEvent event = buildEvent("30.00", "MRC-100", "BR");

        FraudEvaluation evaluation = engine.evaluate(event, Optional.of(last), 4, now);

        assertTrue(evaluation.fraudulent());
        assertTrue(evaluation.reasons().contains("HIGH_VELOCITY"));
        assertTrue(evaluation.reasons().contains("COUNTRY_CHANGE_IN_SHORT_WINDOW"));
        assertEquals(65, evaluation.riskScore());
    }

    @Test
    void normalTransactionShouldNotTriggerFraud() {
        FraudRulesEngine engine = new FraudRulesEngine(defaultRules());
        TransactionCreatedEvent event = buildEvent("25.00", "MRC-100", "US");

        FraudEvaluation evaluation = engine.evaluate(event, Optional.empty(), 1, Instant.now());

        assertFalse(evaluation.fraudulent());
        assertTrue(evaluation.reasons().isEmpty());
        assertEquals(0, evaluation.riskScore());
    }

    private FraudRulesProperties defaultRules() {
        FraudRulesProperties properties = new FraudRulesProperties();
        properties.setHighAmountThreshold(new BigDecimal("10000.00"));
        properties.setVelocityMaxTransactions(5);
        properties.setVelocityWindow(Duration.ofMinutes(1));
        properties.setCountryChangeWindow(Duration.ofMinutes(30));
        properties.setHighRiskMerchants(List.of("MRC-999", "MRC-666"));
        return properties;
    }

    private TransactionCreatedEvent buildEvent(String amount, String merchantId, String country) {
        return new TransactionCreatedEvent(
                "event-1",
                Instant.now(),
                "tx-1",
                "user-1",
                new BigDecimal(amount),
                "USD",
                merchantId,
                country,
                PaymentMethod.CARD
        );
    }
}
