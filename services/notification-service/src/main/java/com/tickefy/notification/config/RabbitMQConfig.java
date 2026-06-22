package com.tickefy.notification.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class RabbitMQConfig {

    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setAutoStartup(true);
        return admin;
    }

    @Bean
    public ApplicationRunner rabbitInitializer(RabbitAdmin rabbitAdmin) {
        return args -> {
            log.info("[RabbitMQ] Initializing exchanges and queues...");
            rabbitAdmin.initialize();
            log.info("[RabbitMQ] Initialization complete.");
        };
    }

    public static final String EXCHANGE_EVENTS = "tickefy.events";
    public static final String EXCHANGE_DLX = "tickefy.dlx";

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange(EXCHANGE_EVENTS, true, false);
    }

    @Bean
    public TopicExchange dlxExchange() {
        return new TopicExchange(EXCHANGE_DLX, true, false);
    }

    // Helper methods for creating queue, dlq and bindings
    private Queue createQueue(String queueName, String dlqName) {
        return QueueBuilder.durable(queueName)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", dlqName)
                .build();
    }

    private Queue createDlq(String dlqName) {
        return QueueBuilder.durable(dlqName).build();
    }

    private Binding createBinding(Queue queue, TopicExchange exchange, String routingKey) {
        return BindingBuilder.bind(queue).to(exchange).with(routingKey);
    }

    // ==========================================
    // Order Paid
    // ==========================================
    public static final String QUEUE_ORDER_PAID = "notification.order-paid";
    public static final String DLQ_ORDER_PAID = "notification.order-paid.dlq";

    @Bean public Queue orderPaidQueue() { return createQueue(QUEUE_ORDER_PAID, DLQ_ORDER_PAID); }
    @Bean public Queue orderPaidDlq() { return createDlq(DLQ_ORDER_PAID); }
    @Bean public Binding orderPaidBinding(Queue orderPaidQueue, TopicExchange eventsExchange) { return createBinding(orderPaidQueue, eventsExchange, "order.paid"); }
    @Bean public Binding orderPaidDlqBinding(Queue orderPaidDlq, TopicExchange dlxExchange) { return createBinding(orderPaidDlq, dlxExchange, DLQ_ORDER_PAID); }

    // ==========================================
    // Order Payment Failed
    // ==========================================
    public static final String QUEUE_ORDER_PAYMENT_FAILED = "notification.order-payment-failed";
    public static final String DLQ_ORDER_PAYMENT_FAILED = "notification.order-payment-failed.dlq";

    @Bean public Queue orderPaymentFailedQueue() { return createQueue(QUEUE_ORDER_PAYMENT_FAILED, DLQ_ORDER_PAYMENT_FAILED); }
    @Bean public Queue orderPaymentFailedDlq() { return createDlq(DLQ_ORDER_PAYMENT_FAILED); }
    @Bean public Binding orderPaymentFailedBinding(Queue orderPaymentFailedQueue, TopicExchange eventsExchange) { return createBinding(orderPaymentFailedQueue, eventsExchange, "order.payment.failed"); }
    @Bean public Binding orderPaymentFailedDlqBinding(Queue orderPaymentFailedDlq, TopicExchange dlxExchange) { return createBinding(orderPaymentFailedDlq, dlxExchange, DLQ_ORDER_PAYMENT_FAILED); }

    // ==========================================
    // Order Expired
    // ==========================================
    public static final String QUEUE_ORDER_EXPIRED = "notification.order-expired";
    public static final String DLQ_ORDER_EXPIRED = "notification.order-expired.dlq";

    @Bean public Queue orderExpiredQueue() { return createQueue(QUEUE_ORDER_EXPIRED, DLQ_ORDER_EXPIRED); }
    @Bean public Queue orderExpiredDlq() { return createDlq(DLQ_ORDER_EXPIRED); }
    @Bean public Binding orderExpiredBinding(Queue orderExpiredQueue, TopicExchange eventsExchange) { return createBinding(orderExpiredQueue, eventsExchange, "order.expired"); }
    @Bean public Binding orderExpiredDlqBinding(Queue orderExpiredDlq, TopicExchange dlxExchange) { return createBinding(orderExpiredDlq, dlxExchange, DLQ_ORDER_EXPIRED); }

    // ==========================================
    // Order Cancelled
    // ==========================================
    public static final String QUEUE_ORDER_CANCELLED = "notification.order-cancelled";
    public static final String DLQ_ORDER_CANCELLED = "notification.order-cancelled.dlq";

    @Bean public Queue orderCancelledQueue() { return createQueue(QUEUE_ORDER_CANCELLED, DLQ_ORDER_CANCELLED); }
    @Bean public Queue orderCancelledDlq() { return createDlq(DLQ_ORDER_CANCELLED); }
    @Bean public Binding orderCancelledBinding(Queue orderCancelledQueue, TopicExchange eventsExchange) { return createBinding(orderCancelledQueue, eventsExchange, "order.cancelled"); }
    @Bean public Binding orderCancelledDlqBinding(Queue orderCancelledDlq, TopicExchange dlxExchange) { return createBinding(orderCancelledDlq, dlxExchange, DLQ_ORDER_CANCELLED); }

    // ==========================================
    // Order Refunded
    // ==========================================
    public static final String QUEUE_ORDER_REFUNDED = "notification.order-refunded";
    public static final String DLQ_ORDER_REFUNDED = "notification.order-refunded.dlq";

    @Bean public Queue orderRefundedQueue() { return createQueue(QUEUE_ORDER_REFUNDED, DLQ_ORDER_REFUNDED); }
    @Bean public Queue orderRefundedDlq() { return createDlq(DLQ_ORDER_REFUNDED); }
    @Bean public Binding orderRefundedBinding(Queue orderRefundedQueue, TopicExchange eventsExchange) { return createBinding(orderRefundedQueue, eventsExchange, "order.refunded"); }
    @Bean public Binding orderRefundedDlqBinding(Queue orderRefundedDlq, TopicExchange dlxExchange) { return createBinding(orderRefundedDlq, dlxExchange, DLQ_ORDER_REFUNDED); }

    // ==========================================
    // Tickets Issued
    // ==========================================
    public static final String QUEUE_TICKETS_ISSUED = "notification.tickets-issued";
    public static final String DLQ_TICKETS_ISSUED = "notification.tickets-issued.dlq";

    @Bean public Queue ticketsIssuedQueue() { return createQueue(QUEUE_TICKETS_ISSUED, DLQ_TICKETS_ISSUED); }
    @Bean public Queue ticketsIssuedDlq() { return createDlq(DLQ_TICKETS_ISSUED); }
    @Bean public Binding ticketsIssuedBinding(Queue ticketsIssuedQueue, TopicExchange eventsExchange) { return createBinding(ticketsIssuedQueue, eventsExchange, "tickets.issued"); }
    @Bean public Binding ticketsIssuedDlqBinding(Queue ticketsIssuedDlq, TopicExchange dlxExchange) { return createBinding(ticketsIssuedDlq, dlxExchange, DLQ_TICKETS_ISSUED); }

    // ==========================================
    // Concert Cancelled
    // ==========================================
    public static final String QUEUE_CONCERT_CANCELLED = "notification.concert-cancelled";
    public static final String DLQ_CONCERT_CANCELLED = "notification.concert-cancelled.dlq";

    @Bean public Queue concertCancelledQueue() { return createQueue(QUEUE_CONCERT_CANCELLED, DLQ_CONCERT_CANCELLED); }
    @Bean public Queue concertCancelledDlq() { return createDlq(DLQ_CONCERT_CANCELLED); }
    @Bean public Binding concertCancelledBinding(Queue concertCancelledQueue, TopicExchange eventsExchange) { return createBinding(concertCancelledQueue, eventsExchange, "concert.cancelled"); }
    @Bean public Binding concertCancelledDlqBinding(Queue concertCancelledDlq, TopicExchange dlxExchange) { return createBinding(concertCancelledDlq, dlxExchange, DLQ_CONCERT_CANCELLED); }
}
