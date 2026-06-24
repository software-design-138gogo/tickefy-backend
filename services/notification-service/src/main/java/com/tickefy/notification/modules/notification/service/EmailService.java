package com.tickefy.notification.modules.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Service responsible for sending emails.
 *
 * <p>Defensive design: any mail delivery failure is logged as ERROR but does NOT throw an
 * exception, ensuring in-app notification persistence is never affected by mail issues.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from:noreply@tickefy.vn}")
    private String fromAddress;

    @Value("${app.mail.from-name:Tickefy}")
    private String fromName;

    /**
     * Sends an HTML email.
     *
     * <p>Failures are swallowed and logged — callers must not rely on a thrown exception to detect
     * mail errors.
     *
     * @param to recipient email address
     * @param subject email subject line
     * @param htmlContent fully rendered HTML body
     */
    public void sendEmail(String to, String subject, String htmlContent) {
        try {
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress, fromName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);
            mailSender.send(message);
            log.info("[EmailService] Email sent successfully to={} subject={}", to, subject);
        } catch (Exception ex) {
            // Defensive: log only, never propagate — in-app notification must not be rolled back
            log.error(
                    "[EmailService] Failed to send email to={} subject={} error={}",
                    to,
                    subject,
                    ex.getMessage(),
                    ex);
        }
    }
}
