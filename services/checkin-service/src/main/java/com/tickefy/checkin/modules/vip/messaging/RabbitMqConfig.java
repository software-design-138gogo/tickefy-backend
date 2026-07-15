package com.tickefy.checkin.modules.vip.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for checkin-service vip-2.
 *
 * Consumes: vip-guest-import.completed (from csv-ingestion OutboxPublisher)
 * Action:   mark vip_cache_meta STALE + record dedup in processed_messages
 */
@Configuration
public class RabbitMqConfig {

    // §6.6 DLQ per-queue-name (1 consumer/rk — no fan-out collision)
    private static final String QUEUE  = "checkin.vip-guest-import-completed";
    private static final String DLQ    = "checkin.vip-guest-import-completed.dlq";
    private static final String DL_RK  = "checkin.vip-guest-import-completed.dlq";
    private static final String RK     = "vip-guest-import.completed"; // KHOP csv CsvEvents.RoutingKey.COMPLETED

    @Value("${app.messaging.exchange:tickefy.exchange}")
    private String exchange;

    @Value("${app.messaging.dlx:tickefy.dlx}")
    private String dlxName;

    // ── Exchanges ─────────────────────────────────────────────────────────────

    @Bean
    public TopicExchange tickefyExchange() {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    public TopicExchange tickefyDlx() {
        return new TopicExchange(dlxName, true, false);
    }

    // ── Queues ────────────────────────────────────────────────────────────────

    @Bean
    public Queue vipImportQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(dlxName)
                .deadLetterRoutingKey(DL_RK)
                .build();
    }

    @Bean
    public Queue vipImportDlq() {
        return QueueBuilder.durable(DLQ).build();
    }

    // ── Bindings ──────────────────────────────────────────────────────────────

    @Bean
    public Binding vipImportBinding(Queue vipImportQueue, TopicExchange tickefyExchange) {
        return BindingBuilder.bind(vipImportQueue).to(tickefyExchange).with(RK);
    }

    @Bean
    public Binding vipImportDlqBinding(Queue vipImportDlq, TopicExchange tickefyDlx) {
        return BindingBuilder.bind(vipImportDlq).to(tickefyDlx).with(DL_RK);
    }

    // ── Converter ─────────────────────────────────────────────────────────────

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // ── Listener factory ──────────────────────────────────────────────────────

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter,
            @Value("${app.messaging.listener-auto-startup:true}") boolean autoStartup) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setAutoStartup(autoStartup);           // §6.12 — test yml sets false (no broker needed)
        factory.setDefaultRequeueRejected(false);      // poison → DLQ, never requeue loop
        return factory;
    }
}
