package com.fraud.alert.api;

import com.fraud.alert.model.Alert;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertMapperTest {

    private final AlertMapper mapper = Mappers.getMapper(AlertMapper.class);

    @Test
    void toResponseShouldSplitCommaSeparatedReasons() {
        Alert alert = new Alert(
                "a-1",
                "tx-1",
                "user-1",
                90,
                "HIGH_AMOUNT,HIGH_VELOCITY",
                Instant.parse("2026-01-01T10:00:00Z")
        );

        AlertResponse response = mapper.toResponse(alert);

        assertEquals("a-1", response.id());
        assertEquals("tx-1", response.transactionId());
        assertEquals("user-1", response.userId());
        assertEquals(90, response.riskScore());
        assertEquals(List.of("HIGH_AMOUNT", "HIGH_VELOCITY"), response.reasons());
    }

    @Test
    void toResponseShouldReturnEmptyReasonsWhenReasonsStringIsBlank() {
        Alert alert = new Alert(
                "a-2",
                "tx-2",
                "user-2",
                55,
                "   ",
                Instant.parse("2026-01-01T10:05:00Z")
        );

        AlertResponse response = mapper.toResponse(alert);

        assertTrue(response.reasons().isEmpty());
    }
}
