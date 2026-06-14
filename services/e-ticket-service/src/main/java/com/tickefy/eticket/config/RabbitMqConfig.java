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

    @Value("${app.messaging.exchange:tickefy.exchange}")
    private String exchange;

    @Value("${app.messaging.queue.order-paid:ticket-service.order-paid.queue}")
    private String orderPaidQueue;

    // ── Exchange ──────────────────────────────────────────────────────────────

    @Bean
    public TopicExchange tickefyExchange() {
        return new TopicExchange(exchange, true, false);
    }

    // ── Queues ────────────────────────────────────────────────────────────────

    @Bean
    public Queue orderPaidQueue() {
        return QueueBuilder.durable(orderPaidQueue).build();
    }

    // ── Bindings ──────────────────────────────────────────────────────────────

    @Bean
    public Binding orderPaidBinding(Queue orderPaidQueue, TopicExchange tickefyExchange) {
        return BindingBuilder.bind(orderPaidQueue).to(tickefyExchange).with("order.paid");
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
