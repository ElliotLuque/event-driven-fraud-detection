package com.fraud.alert.notification;

import com.fraud.alert.service.NotificationGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationConfig {

    private static final Logger log = LoggerFactory.getLogger(NotificationConfig.class);

    @Bean
    public NotificationGateway notificationGateway(
            NotificationProperties props,
            List<NotificationChannel> channels
    ) {
        return new CompositeNotificationGateway(channels);
    }

    @Bean
    @ConditionalOnProperty(name = "app.notification.log-enabled", havingValue = "true", matchIfMissing = true)
    public NotificationChannel logNotificationChannel() {
        return new LogNotificationChannel();
    }

    @Bean
    @ConditionalOnProperty(name = "app.notification.email.enabled", havingValue = "true")
    public NotificationChannel emailNotificationChannel(
            JavaMailSender mailSender,
            NotificationProperties props
    ) {
        NotificationProperties.EmailProperties email = props.email();
        if (email.to().isEmpty()) {
            log.warn("Email notification enabled but no recipients configured (app.notification.email.to). Skipping.");
            return new LogNotificationChannel();
        }
        return new EmailNotificationChannel(mailSender, email.from(), email.to());
    }
}
