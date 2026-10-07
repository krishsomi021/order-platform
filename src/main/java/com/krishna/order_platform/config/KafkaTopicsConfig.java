package com.krishna.order_platform.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
public class KafkaTopicsConfig {

    public static final String ORDER_CREATED = "order.created";

    // Boot's KafkaAdmin creates this on startup. Broker auto-create is off, so this is the only way it exists.
    @Bean
    NewTopic orderCreatedTopic() {
        return TopicBuilder.name(ORDER_CREATED).partitions(3).replicas(1).build();
    }
}