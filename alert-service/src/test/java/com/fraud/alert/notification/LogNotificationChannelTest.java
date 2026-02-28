package com.fraud.alert.notification;

import com.fraud.alert.model.Alert;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LogNotificationChannelTest {

    @Test
    void shouldLogWithoutThrowing() {
        LogNotificationChannel channel = new LogNotificationChannel();
        Alert alert = new Alert(
                "a-1", "tx-1", "user-1", 85,
                "HIGH_AMOUNT", Instant.parse("2026-01-01T10:00:00Z")
        );

        assertDoesNotThrow(() -> channel.send(alert));
    }

    @Test
    void channelNameShouldBeLog() {
        assertEquals("log", new LogNotificationChannel().channelName());
    }
}
