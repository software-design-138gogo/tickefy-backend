package com.tickefy.notification.modules.notification.strategy;

import com.tickefy.notification.modules.notification.service.EmailService;
import com.tickefy.notification.modules.notification.service.EmailTemplateService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailNotificationStrategy implements NotificationChannelStrategy {

    private final EmailService emailService;
    private final EmailTemplateService emailTemplateService;

    @Override
    public boolean supports(NotificationContext context) {
        return context.getEmailTemplateName() != null && context.getRecipientEmail() != null;
    }

    @Override
    public void send(NotificationContext context) {
        log.info("[EmailNotificationStrategy] Rendering and sending email to={} template={}",
                context.getRecipientEmail(), context.getEmailTemplateName());

        Map<String, Object> templateVars = context.getTemplateVars();
        String emailHtml = emailTemplateService.render(context.getEmailTemplateName(), templateVars);
        
        emailService.sendEmail(
                context.getRecipientEmail(),
                context.getEmailSubject() != null ? context.getEmailSubject() : "Tickefy Notification",
                emailHtml
        );
    }
}
