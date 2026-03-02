package com.fraud.alert.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app.notification")
public record NotificationProperties(
        boolean logEnabled,
        EmailProperties email
) {
    public NotificationProperties {
        if (email == null) {
            email = new EmailProperties(false, null, List.of(), 75);
        }
    }

    public record EmailProperties(
            boolean enabled,
            String from,
            List<String> to,
            int minRiskScore
    ) {
        public EmailProperties {
            if (to == null) to = List.of();
        }
    }
}
