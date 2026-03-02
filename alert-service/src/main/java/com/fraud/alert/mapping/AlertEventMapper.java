package com.fraud.alert.mapping;

import com.fraud.alert.events.FraudDetectedEvent;
import com.fraud.alert.model.Alert;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;

@Component
public class AlertEventMapper {

    public Alert toAlert(FraudDetectedEvent event, Instant createdAt, String traceId) {
        return new Alert(
                UUID.randomUUID().toString(),
                event.transactionId(),
                event.userId(),
                event.riskScore(),
                joinReasons(event.reasons()),
                createdAt,
                event.eventId(),
                traceId
        );
    }

    String joinReasons(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return "UNSPECIFIED_RULE";
        }

        StringJoiner joiner = new StringJoiner(",");
        reasons.forEach(joiner::add);
        String joinedReasons = joiner.toString();
        return joinedReasons.isBlank() ? "UNSPECIFIED_RULE" : joinedReasons;
    }
}
