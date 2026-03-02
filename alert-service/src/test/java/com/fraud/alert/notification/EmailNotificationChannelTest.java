package com.fraud.alert.notification;

import com.fraud.alert.model.Alert;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailNotificationChannelTest {

    @Mock
    private JavaMailSender mailSender;

    private EmailNotificationChannel channel;

    private final Alert highRiskAlert = new Alert(
            "a-1", "tx-1", "user-1", 85,
            "HIGH_AMOUNT,HIGH_RISK_MERCHANT",
            Instant.parse("2026-01-01T10:00:00Z")
    );

    private final Alert lowRiskAlert = new Alert(
            "a-2", "tx-2", "user-2", 45,
            "HIGH_AMOUNT",
            Instant.parse("2026-01-01T10:00:00Z")
    );

    @BeforeEach
    void setUp() {
        channel = new EmailNotificationChannel(
                mailSender,
                "fraud@test.com",
                List.of("security@test.com", "ops@test.com"),
                75
        );
    }

    @Test
    void shouldSendEmailWhenRiskScoreExceedsThreshold() throws Exception {
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        channel.send(highRiskAlert);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
    }

    @Test
    void shouldSkipEmailWhenRiskScoreBelowThreshold() {
        channel.send(lowRiskAlert);

        verify(mailSender, never()).createMimeMessage();
    }

    @Test
    void shouldNotThrowWhenMailSenderFails() {
        when(mailSender.createMimeMessage()).thenThrow(new RuntimeException("SMTP connection refused"));

        assertDoesNotThrow(() -> channel.send(highRiskAlert));
    }

    @Test
    void channelNameShouldBeEmail() {
        assertEquals("email", channel.channelName());
    }
}
