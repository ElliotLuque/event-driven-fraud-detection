package com.fraud.detection.rules;

import com.fraud.detection.config.FraudRulesProperties;
import com.fraud.detection.events.TransactionCreatedEvent;
import com.fraud.detection.model.UserTransactionHistory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
public class FraudRulesEngine {

    private final FraudRulesProperties rules;

    public FraudRulesEngine(FraudRulesProperties rules) {
        this.rules = rules;
    }

    public FraudEvaluation evaluate(
            TransactionCreatedEvent event,
            Optional<UserTransactionHistory> lastTransaction,
            long recentTransactionsCount,
            Instant referenceTime
    ) {
        List<String> reasons = new ArrayList<>();
        int score = 0;

        if (event.amount() != null && event.amount().compareTo(rules.getHighAmountThreshold()) > 0) {
            reasons.add("HIGH_AMOUNT");
            score += 45;
        }

        if (recentTransactionsCount + 1 >= rules.getVelocityMaxTransactions()) {
            reasons.add("HIGH_VELOCITY");
            score += 35;
        }

        if (isCountryChangeSuspicious(event, lastTransaction, referenceTime)) {
            reasons.add("COUNTRY_CHANGE_IN_SHORT_WINDOW");
            score += 30;
        }

        if (isHighRiskMerchant(event.merchantId())) {
            reasons.add("HIGH_RISK_MERCHANT");
            score += 25;
        }

        return new FraudEvaluation(!reasons.isEmpty(), Math.min(100, score), List.copyOf(reasons));
    }

    private boolean isCountryChangeSuspicious(
            TransactionCreatedEvent event,
            Optional<UserTransactionHistory> lastTransaction,
            Instant referenceTime
    ) {
        if (lastTransaction.isEmpty()) {
            return false;
        }

        UserTransactionHistory previous = lastTransaction.get();
        if (event.country() == null || previous.getCountry() == null) {
            return false;
        }

        boolean changedCountry = !previous.getCountry().equalsIgnoreCase(event.country());
        boolean insideWindow = previous.getOccurredAt().isAfter(referenceTime.minus(rules.getCountryChangeWindow()));
        return changedCountry && insideWindow;
    }

    private boolean isHighRiskMerchant(String merchantId) {
        if (merchantId == null) {
            return false;
        }
        String normalizedMerchant = merchantId.toUpperCase(Locale.ROOT);
        return rules.getHighRiskMerchants()
                .stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(normalizedMerchant::equals);
    }
}
