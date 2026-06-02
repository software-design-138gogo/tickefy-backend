package com.tickefy.eticket.database;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    @Value("${app.database.schema:public}")
    private String schema;

    @PostConstruct
    public void logDatabaseSchema() {
        log.info("Using database schema: {}", schema);
    }
}
