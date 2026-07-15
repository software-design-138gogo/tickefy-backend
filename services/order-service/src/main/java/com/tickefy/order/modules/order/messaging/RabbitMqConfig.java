package com.tickefy.order.modules.order.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AMQP topology for order-service.
 *
 * <p>Consumes: {@code payment.succeeded}, {@code payment.failed} (from Payment / dev stub).
 * Publishes (via outbox drainer): {@code order.paid}, {@code order.payment.failed}, {@code order.expired}.
 *
 * <p>Every consume queue has a dead-letter queue. The listener factory uses
 * {@code setDefaultRequeueRejected(false)} so a poison message is dead-lettered, NOT requeued forever.
 */
@Configuration
public class RabbitMqConfig {

    public static final String PAYMENT_SUCCEEDED_QUEUE = "order-service.payment-succeeded.queue";
    public static final String PAYMENT_FAILED_QUEUE = "order-service.payment-failed.queue";
    public static final String CONCERT_CANCELLED_QUEUE = "order-service.concert-cancelled.queue";

    private static final String PAYMENT_SUCCEEDED_RK = "payment.succeeded";
    private static final String PAYMENT_FAILED_RK = "payment.failed";
    private static final String CONCERT_CANCELLED_RK = "concert.cancelled";

    @Value("${app.messaging.exchange:tickefy.exchange}")
    private String exchangeName;

    @Value("${app.messaging.dlx:tickefy.dlx}")
    private String dlxName;

    @Bean
    public TopicExchange tickefyExchange() {
        return new TopicExchange(exchangeName, true, false);
    }

    @Bean
    public TopicExchange tickefyDlx() {
        return new TopicExchange(dlxName, true, false);
    }

    // ── payment.succeeded ────────────────────────────────────────────────────
    @Bean
    public Queue paymentSucceededQueue() {
        return QueueBuilder.durable(PAYMENT_SUCCEEDED_QUEUE)
                .deadLetterExchange(dlxName)
                .deadLetterRoutingKey(PAYMENT_SUCCEEDED_RK + ".dlq")
                .build();
    }

    @Bean
    public Queue paymentSucceededDlq() {
        return QueueBuilder.durable(PAYMENT_SUCCEEDED_QUEUE + ".dlq").build();
    }

    @Bean
    public Binding paymentSucceededBinding(Queue paymentSucceededQueue, TopicExchange tickefyExchange) {
        return BindingBuilder.bind(paymentSucceededQueue).to(tickefyExchange).with(PAYMENT_SUCCEEDED_RK);
    }

    @Bean
    public Binding paymentSucceededDlqBinding(Queue paymentSucceededDlq, TopicExchange tickefyDlx) {
        return BindingBuilder.bind(paymentSucceededDlq).to(tickefyDlx).with(PAYMENT_SUCCEEDED_RK + ".dlq");
    }

    // ── payment.failed ───────────────────────────────────────────────────────
    @Bean
    public Queue paymentFailedQueue() {
        return QueueBuilder.durable(PAYMENT_FAILED_QUEUE)
                .deadLetterExchange(dlxName)
                .deadLetterRoutingKey(PAYMENT_FAILED_RK + ".dlq")
                .build();
    }

    @Bean
    public Queue paymentFailedDlq() {
        return QueueBuilder.durable(PAYMENT_FAILED_QUEUE + ".dlq").build();
    }

    @Bean
    public Binding paymentFailedBinding(Queue paymentFailedQueue, TopicExchange tickefyExchange) {
        return BindingBuilder.bind(paymentFailedQueue).to(tickefyExchange).with(PAYMENT_FAILED_RK);
    }

    @Bean
    public Binding paymentFailedDlqBinding(Queue paymentFailedDlq, TopicExchange tickefyDlx) {
        return BindingBuilder.bind(paymentFailedDlq).to(tickefyDlx).with(PAYMENT_FAILED_RK + ".dlq");
    }

    // ── concert.cancelled (multi-consumer fan-out: order + inventory + notification + e-ticket) ──
    // DLQ routing key is QUEUE-NAME-based (§6.6) — NOT rk-based — so this queue's poison messages do
    // not pollute the other consumers' DLQs that share routing key concert.cancelled.
    @Bean
    public Queue concertCancelledQueue() {
        return QueueBuilder.durable(CONCERT_CANCELLED_QUEUE)
                .deadLetterExchange(dlxName)
                .deadLetterRoutingKey(dlRk(CONCERT_CANCELLED_QUEUE))
                .build();
    }

    @Bean
    public Queue concertCancelledDlq() {
        return QueueBuilder.durable(CONCERT_CANCELLED_QUEUE + ".dlq").build();
    }

    @Bean
    public Binding concertCancelledBinding(Queue concertCancelledQueue, TopicExchange tickefyExchange) {
        return BindingBuilder.bind(concertCancelledQueue).to(tickefyExchange).with(CONCERT_CANCELLED_RK);
    }

    @Bean
    public Binding concertCancelledDlqBinding(Queue concertCancelledDlq, TopicExchange tickefyDlx) {
        return BindingBuilder.bind(concertCancelledDlq).to(tickefyDlx).with(dlRk(CONCERT_CANCELLED_QUEUE));
    }

    /**
     * Dead-letter routing key derived from the QUEUE NAME (§6.6: per-queue, not per-routing-key).
     * Strips the ".queue" suffix then appends ".dlq" — mirrors inventory-service convention so a
     * fan-out routing key (e.g. concert.cancelled) never cross-pollinates sibling DLQs.
     */
    private static String dlRk(String queueName) {
        String base = queueName.endsWith(".queue")
                ? queueName.substring(0, queueName.length() - ".queue".length())
                : queueName;
        return base + ".dlq";
    }

    // ── Converters / template / listener factory ─────────────────────────────
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter,
            @Value("${app.messaging.listener-auto-startup:true}") boolean listenerAutoStartup) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setDefaultRequeueRejected(false); // poison -> DLQ, not infinite requeue
        factory.setAutoStartup(listenerAutoStartup);
        return factory;
    }
}
