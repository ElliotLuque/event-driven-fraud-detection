package com.fraud.alert.api;

import com.fraud.alert.model.Alert;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class AlertMapper {

    public AlertResponse toResponse(Alert alert) {
        if (alert == null) {
            return null;
        }

        return new AlertResponse(
                alert.getId(),
                alert.getTransactionId(),
                alert.getUserId(),
                alert.getRiskScore(),
                splitReasons(alert.getReasons()),
                alert.getCreatedAt(),
                alert.getSourceEventId(),
                alert.getTraceId()
        );
    }

    List<String> splitReasons(String reasons) {
        if (reasons == null || reasons.isBlank()) {
            return List.of();
        }
        return Arrays.stream(reasons.split(",")).toList();
    }
}
