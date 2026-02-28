package com.fraud.alert.notification;

import com.fraud.alert.model.Alert;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CompositeNotificationGatewayTest {

    private final Alert alert = new Alert(
            "a-1", "tx-1", "user-1", 85,
            "HIGH_AMOUNT,HIGH_RISK_MERCHANT",
            Instant.parse("2026-01-01T10:00:00Z")
    );

    @Test
    void shouldDelegateToAllChannels() {
        NotificationChannel ch1 = mock(NotificationChannel.class);
        NotificationChannel ch2 = mock(NotificationChannel.class);
        var gateway = new CompositeNotificationGateway(List.of(ch1, ch2));

        gateway.notifyFraud(alert);

        verify(ch1).send(alert);
        verify(ch2).send(alert);
    }

    @Test
    void shouldContinueWhenOneChannelFails() {
        NotificationChannel failing = mock(NotificationChannel.class);
        NotificationChannel healthy = mock(NotificationChannel.class);
        doThrow(new RuntimeException("SMTP down")).when(failing).send(alert);
        var gateway = new CompositeNotificationGateway(List.of(failing, healthy));

        assertDoesNotThrow(() -> gateway.notifyFraud(alert));

        verify(failing).send(alert);
        verify(healthy).send(alert);
    }
}
