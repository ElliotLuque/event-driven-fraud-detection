package com.fraud.alert.api;

import com.fraud.alert.model.Alert;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class AlertMapper {

    public AlertResponse toResponse(Alert alert) {
        List<String> reasons = alert.getReasons().isBlank()
                ? List.of()
                : Arrays.stream(alert.getReasons().split(",")).toList();

        return new AlertResponse(
                alert.getId(),
                alert.getTransactionId(),
                alert.getUserId(),
                alert.getRiskScore(),
                reasons,
                alert.getCreatedAt()
        );
    }
}
