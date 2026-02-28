package com.fraud.alert.notification;

import com.fraud.alert.model.Alert;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * Notification channel that sends fraud alerts via email using SMTP.
 */
public class EmailNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationChannel.class);
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
            .withZone(ZoneOffset.UTC);

    private final JavaMailSender mailSender;
    private final String from;
    private final List<String> to;

    public EmailNotificationChannel(JavaMailSender mailSender, String from, List<String> to) {
        this.mailSender = mailSender;
        this.from = from;
        this.to = to;
    }

    @Override
    public void send(Alert alert) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(from);
            helper.setTo(to.toArray(String[]::new));
            helper.setSubject(buildSubject(alert));
            helper.setText(buildHtmlBody(alert), true);

            mailSender.send(message);
            log.info("Email notification sent for alert {} to {}", alert.getId(), to);
        } catch (Exception ex) {
            log.error("Failed to send email notification for alert {}: {}", alert.getId(), ex.getMessage(), ex);
        }
    }

    @Override
    public String channelName() {
        return "email";
    }

    private String buildSubject(Alert alert) {
        return String.format("[FRAUD ALERT] Risk score %d - User %s - Transaction %s",
                alert.getRiskScore(), alert.getUserId(), alert.getTransactionId());
    }

    private String buildHtmlBody(Alert alert) {
        String severityColor = getSeverityColor(alert.getRiskScore());
        String severityLabel = getSeverityLabel(alert.getRiskScore());
        List<String> reasons = parseReasons(alert.getReasons());

        StringBuilder reasonsList = new StringBuilder();
        for (String reason : reasons) {
            reasonsList.append(String.format("<li style=\"padding:4px 0\">%s</li>", escapeHtml(reason)));
        }

        return String.format("""
                <div style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto">
                  <div style="background:%s;color:white;padding:16px 24px;border-radius:8px 8px 0 0">
                    <h2 style="margin:0">Fraud Alert — %s</h2>
                  </div>
                  <div style="border:1px solid #e0e0e0;border-top:none;padding:24px;border-radius:0 0 8px 8px">
                    <table style="width:100%%;border-collapse:collapse">
                      <tr>
                        <td style="padding:8px 0;font-weight:bold;color:#555">Transaction ID</td>
                        <td style="padding:8px 0"><code>%s</code></td>
                      </tr>
                      <tr>
                        <td style="padding:8px 0;font-weight:bold;color:#555">User ID</td>
                        <td style="padding:8px 0"><code>%s</code></td>
                      </tr>
                      <tr>
                        <td style="padding:8px 0;font-weight:bold;color:#555">Risk Score</td>
                        <td style="padding:8px 0">
                          <span style="background:%s;color:white;padding:4px 12px;border-radius:12px;font-weight:bold">%d / 100</span>
                        </td>
                      </tr>
                      <tr>
                        <td style="padding:8px 0;font-weight:bold;color:#555">Detected At</td>
                        <td style="padding:8px 0">%s</td>
                      </tr>
                    </table>
                    <h3 style="margin:20px 0 8px;color:#333">Triggered Rules</h3>
                    <ul style="margin:0;padding-left:20px;color:#333">
                      %s
                    </ul>
                    <hr style="margin:24px 0;border:none;border-top:1px solid #e0e0e0">
                    <p style="color:#888;font-size:12px;margin:0">
                      This is an automated alert from the Fraud Detection System. Alert ID: %s
                    </p>
                  </div>
                </div>
                """,
                severityColor, severityLabel,
                escapeHtml(alert.getTransactionId()),
                escapeHtml(alert.getUserId()),
                severityColor, alert.getRiskScore(),
                TIMESTAMP_FMT.format(alert.getCreatedAt()),
                reasonsList,
                escapeHtml(alert.getId())
        );
    }

    private static String getSeverityColor(int riskScore) {
        if (riskScore >= 80) return "#d32f2f";
        if (riskScore >= 60) return "#f57c00";
        if (riskScore >= 40) return "#fbc02d";
        return "#388e3c";
    }

    private static String getSeverityLabel(int riskScore) {
        if (riskScore >= 80) return "CRITICAL";
        if (riskScore >= 60) return "HIGH";
        if (riskScore >= 40) return "MEDIUM";
        return "LOW";
    }

    private static List<String> parseReasons(String reasons) {
        if (reasons == null || reasons.isBlank()) return List.of("UNSPECIFIED");
        return Arrays.stream(reasons.split(",")).map(String::trim).toList();
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
