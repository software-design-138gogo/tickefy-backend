package com.tickefy.checkin.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ApiLinkLogger {

    private static final Logger log = LoggerFactory.getLogger(ApiLinkLogger.class);

    private final String serviceName;
    private final String port;

    public ApiLinkLogger(
            @Value("${spring.application.name:checkin-service}") String serviceName,
            @Value("${server.port:8088}") String port) {
        this.serviceName = serviceName;
        this.port = port;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logApiLinks() {
        String baseUrl = "http://localhost:" + port;
        log.info("{} ready. Health: {}/health", serviceName, baseUrl);
        log.info("{} Swagger UI: {}/swagger-ui/index.html", serviceName, baseUrl);
        log.info("{} OpenAPI JSON: {}/v3/api-docs", serviceName, baseUrl);
    }
}
