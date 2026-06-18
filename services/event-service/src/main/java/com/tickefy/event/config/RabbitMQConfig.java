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

    public static final String EXCHANGE_EVENTS = "tickefy.events";
    public static final String EXCHANGE_DLX = "tickefy.dlx";
    
    public static final String QUEUE_CONCERT_INTRODUCTION = "event.concert-introduction-generated";
    public static final String DLQ_CONCERT_INTRODUCTION = "event.concert-introduction-generated.dlq";

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

    @Bean
    public Queue concertIntroductionQueue() {
        return QueueBuilder.durable(QUEUE_CONCERT_INTRODUCTION)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", DLQ_CONCERT_INTRODUCTION)
                .build();
    }

    @Bean
    public Queue concertIntroductionDlq() {
        return QueueBuilder.durable(DLQ_CONCERT_INTRODUCTION).build();
    }

    @Bean
    public Binding concertIntroductionBinding(Queue concertIntroductionQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(concertIntroductionQueue).to(eventsExchange).with("concert.introduction.generated");
    }

    @Bean
    public Binding concertIntroductionDlqBinding(Queue concertIntroductionDlq, TopicExchange dlxExchange) {
        return BindingBuilder.bind(concertIntroductionDlq).to(dlxExchange).with(DLQ_CONCERT_INTRODUCTION);
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
