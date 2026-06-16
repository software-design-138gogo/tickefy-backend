package com.tickefy.eticket.config;

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
 * RabbitMQ topology for e-ticket-service.
 *
 * Consumes: order.paid (when order-service confirms payment)
 * Publishes: ticket.issued (consumed by notification-service)
 */
@Configuration
public class RabbitMqConfig {

    /** Dead-letter routing key — per-QUEUE (NOT order.paid.dlq). order.paid has 2 consumers
     * (inventory + e-ticket); a shared <rk>.dlq would route e-ticket's poison into inventory's DLQ
     * too (tickefy.dlx is topic). A queue-name-based key keeps each service's DLQ isolated. */
    private static final String ORDER_PAID_DLQ = "ticket-service.order-paid.queue.dlq";
    private static final String ORDER_PAID_DL_RK = "ticket-service.order-paid.dlq";

    @Value("${app.messaging.exchange:tickefy.exchange}")
    private String exchange;

    @Value("${app.messaging.dlx:tickefy.dlx}")
    private String dlxName;

    @Value("${app.messaging.queue.order-paid:ticket-service.order-paid.queue}")
    private String orderPaidQueue;

    // ── Exchange ──────────────────────────────────────────────────────────────

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
    public Queue orderPaidQueue() {
        return QueueBuilder.durable(orderPaidQueue)
                .deadLetterExchange(dlxName)
                .deadLetterRoutingKey(ORDER_PAID_DL_RK)
                .build();
    }

    @Bean
    public Queue orderPaidDlq() {
        return QueueBuilder.durable(ORDER_PAID_DLQ).build();
    }

    // ── Bindings ──────────────────────────────────────────────────────────────

    @Bean
    public Binding orderPaidBinding(Queue orderPaidQueue, TopicExchange tickefyExchange) {
        return BindingBuilder.bind(orderPaidQueue).to(tickefyExchange).with("order.paid");
    }

    @Bean
    public Binding orderPaidDlqBinding(Queue orderPaidDlq, TopicExchange tickefyDlx) {
        return BindingBuilder.bind(orderPaidDlq).to(tickefyDlx).with(ORDER_PAID_DL_RK);
    }

    // ── Converters ────────────────────────────────────────────────────────────

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter,
            @Value("${app.messaging.listener-auto-startup:true}") boolean listenerAutoStartup) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setAutoStartup(listenerAutoStartup);
        factory.setDefaultRequeueRejected(false); // DLQ via policy, not requeue loop
        return factory;
    }
}
