package com.tickefy.inventory.modules.inventory.messaging;

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
 * AMQP topology for inventory-service (Pass 2 consume side).
 *
 * <p>Consumes: {@code order.paid} (commit), {@code order.payment.failed} + {@code order.expired}
 * (release). Each queue has a DLQ; listener factory uses {@code setDefaultRequeueRejected(false)} so a
 * poison message is dead-lettered, not requeued forever. Inventory does NOT publish.
 */
@Configuration
public class RabbitMqConfig {

    public static final String ORDER_PAID_QUEUE = "inventory-service.order-paid.queue";
    public static final String ORDER_PAYMENT_FAILED_QUEUE = "inventory-service.order-payment-failed.queue";
    public static final String ORDER_EXPIRED_QUEUE = "inventory-service.order-expired.queue";

    private static final String ORDER_PAID_RK = "order.paid";
    private static final String ORDER_PAYMENT_FAILED_RK = "order.payment.failed";
    private static final String ORDER_EXPIRED_RK = "order.expired";

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

    @Bean
    public Queue orderPaidQueue() {
        return dlqEnabled(ORDER_PAID_QUEUE);
    }

    @Bean
    public Queue orderPaidDlq() {
        return QueueBuilder.durable(ORDER_PAID_QUEUE + ".dlq").build();
    }

    @Bean
    public Binding orderPaidBinding(Queue orderPaidQueue, TopicExchange tickefyExchange) {
        return BindingBuilder.bind(orderPaidQueue).to(tickefyExchange).with(ORDER_PAID_RK);
    }

    @Bean
    public Binding orderPaidDlqBinding(Queue orderPaidDlq, TopicExchange tickefyDlx) {
        return BindingBuilder.bind(orderPaidDlq).to(tickefyDlx).with(dlRk(ORDER_PAID_QUEUE));
    }

    @Bean
    public Queue orderPaymentFailedQueue() {
        return dlqEnabled(ORDER_PAYMENT_FAILED_QUEUE);
    }

    @Bean
    public Queue orderPaymentFailedDlq() {
        return QueueBuilder.durable(ORDER_PAYMENT_FAILED_QUEUE + ".dlq").build();
    }

    @Bean
    public Binding orderPaymentFailedBinding(Queue orderPaymentFailedQueue, TopicExchange tickefyExchange) {
        return BindingBuilder.bind(orderPaymentFailedQueue).to(tickefyExchange).with(ORDER_PAYMENT_FAILED_RK);
    }

    @Bean
    public Binding orderPaymentFailedDlqBinding(Queue orderPaymentFailedDlq, TopicExchange tickefyDlx) {
        return BindingBuilder.bind(orderPaymentFailedDlq).to(tickefyDlx).with(dlRk(ORDER_PAYMENT_FAILED_QUEUE));
    }

    @Bean
    public Queue orderExpiredQueue() {
        return dlqEnabled(ORDER_EXPIRED_QUEUE);
    }

    @Bean
    public Queue orderExpiredDlq() {
        return QueueBuilder.durable(ORDER_EXPIRED_QUEUE + ".dlq").build();
    }

    @Bean
    public Binding orderExpiredBinding(Queue orderExpiredQueue, TopicExchange tickefyExchange) {
        return BindingBuilder.bind(orderExpiredQueue).to(tickefyExchange).with(ORDER_EXPIRED_RK);
    }

    @Bean
    public Binding orderExpiredDlqBinding(Queue orderExpiredDlq, TopicExchange tickefyDlx) {
        return BindingBuilder.bind(orderExpiredDlq).to(tickefyDlx).with(dlRk(ORDER_EXPIRED_QUEUE));
    }

    /**
     * Dead-letter routing key dẫn xuất từ TÊN QUEUE (§6.6: per-queue, KHÔNG per-routing-key).
     * Bỏ hậu tố ".queue" rồi gắn ".dlq":
     * "inventory-service.order-paid.queue" => "inventory-service.order-paid.dlq".
     * Dùng CHUNG cho cả queue-declare lẫn DLQ-binding để DL-rk hai bên khớp tuyệt đối.
     */
    private static String dlRk(String queueName) {
        String base =
                queueName.endsWith(".queue")
                        ? queueName.substring(0, queueName.length() - ".queue".length())
                        : queueName;
        return base + ".dlq";
    }

    private Queue dlqEnabled(String name) {
        return QueueBuilder.durable(name)
                .deadLetterExchange(dlxName)
                .deadLetterRoutingKey(dlRk(name))
                .build();
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
