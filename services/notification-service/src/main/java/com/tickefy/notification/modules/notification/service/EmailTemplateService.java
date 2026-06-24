package com.tickefy.notification.modules.notification.service;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Service for rendering HTML email templates using Thymeleaf.
 */
@Service
@RequiredArgsConstructor
public class EmailTemplateService {

    private final TemplateEngine templateEngine;

    /**
     * Renders a Thymeleaf template with the given variables.
     *
     * @param templateName the name of the template file (without .html extension), e.g., "email/order-paid"
     * @param variables key-value map of variables to inject into the template
     * @return the fully rendered HTML string
     */
    public String render(String templateName, Map<String, Object> variables) {
        Context context = new Context();
        context.setVariables(variables);
        return templateEngine.process(templateName, context);
    }
}
