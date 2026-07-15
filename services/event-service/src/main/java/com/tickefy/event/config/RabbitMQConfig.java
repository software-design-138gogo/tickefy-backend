package com.tickefy.event.config;

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
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class RabbitMQConfig {

    /**
     * Khai báo RabbitAdmin tường minh để Spring AMQP biết đây là admin
     * dùng để tự động khai báo (auto-declare) Exchange/Queue/Binding.
     */
    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setAutoStartup(true);
        return admin;
    }

    /**
     * Force khai báo tất cả Queue/Exchange ngay khi ứng dụng khởi động xong,
     * không chờ OutboxDrainer chạy lần đầu.
     */
    @Bean
    public ApplicationRunner rabbitInitializer(RabbitAdmin rabbitAdmin) {
        return args -> {
            log.info("[RabbitMQ] Initializing exchanges and queues...");
            rabbitAdmin.initialize();
            log.info("[RabbitMQ] Initialization complete.");
        };
    }

    public static final String ROUTING_KEY_CONCERT_INTRODUCTION = "concert.introduction.generated";

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public TopicExchange eventsExchange(
            @Value("${app.rabbitmq.exchange}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    public TopicExchange dlxExchange(
            @Value("${app.rabbitmq.dlx}") String dlx) {
        return new TopicExchange(dlx, true, false);
    }

    @Bean
    public Queue concertIntroductionQueue(
            @Value("${app.rabbitmq.concert-introduction-queue}") String queue,
            @Value("${app.rabbitmq.dlx}") String dlx,
            @Value("${app.rabbitmq.concert-introduction-dlq}") String dlq) {
        return QueueBuilder.durable(queue)
                .withArgument("x-dead-letter-exchange", dlx)
                .withArgument("x-dead-letter-routing-key", dlq)
                .build();
    }

    @Bean
    public Queue concertIntroductionDlq(
            @Value("${app.rabbitmq.concert-introduction-dlq}") String dlq) {
        return QueueBuilder.durable(dlq).build();
    }

    @Bean
    public Binding concertIntroductionBinding(Queue concertIntroductionQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(concertIntroductionQueue)
                .to(eventsExchange)
                .with(ROUTING_KEY_CONCERT_INTRODUCTION);
    }

    @Bean
    public Binding concertIntroductionDlqBinding(
            Queue concertIntroductionDlq,
            TopicExchange dlxExchange,
            @Value("${app.rabbitmq.concert-introduction-dlq}") String dlq) {
        return BindingBuilder.bind(concertIntroductionDlq).to(dlxExchange).with(dlq);
    }

    // ==========================================
    // DEBUG QUEUE (For testing purposes)
    // ==========================================
    @Bean
    public Queue debugQueue() {
        return QueueBuilder.durable("event-service.debug.queue").build();
    }

    @Bean
    public Binding debugBinding(Queue debugQueue, TopicExchange eventsExchange) {
        // Hứng mọi tin nhắn có routing key bắt đầu bằng "concert." (ví dụ: concert.published, concert.cancelled)
        return BindingBuilder.bind(debugQueue).to(eventsExchange).with("concert.#");
    }
}
