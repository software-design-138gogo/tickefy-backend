package com.tickefy.csvingestion.modules.csvimport.messaging;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AMQP topology for csv-ingestion-service.
 *
 * <p>Publisher-only: declares the shared {@code tickefy.exchange} (durable) so the outbox drainer has
 * a publish target. Queues/DLQ are consumer-side (declared by the checkin consumer in T6/T8) — NOT here.
 * RabbitTemplate is provided by Spring Boot auto-configuration (spring-boot-starter-amqp).
 */
@Configuration
public class RabbitMqConfig {

    @Bean
    public TopicExchange tickefyExchange(@Value("${app.messaging.exchange:tickefy.exchange}") String name) {
        return new TopicExchange(name, true, false); // durable, !autoDelete — idempotent re-declare
    }
}
