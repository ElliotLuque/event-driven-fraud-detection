package com.fraud.detection.rules;

import java.util.List;

public record FraudEvaluation(
        boolean fraudulent,
        int riskScore,
        List<String> reasons
) {
}
