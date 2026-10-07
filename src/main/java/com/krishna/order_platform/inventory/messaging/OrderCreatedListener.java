package com.krishna.order_platform.inventory.messaging;

import com.krishna.order_platform.config.KafkaTopicsConfig;
import com.krishna.order_platform.events.EventEnvelope;
import com.krishna.order_platform.events.OrderCreatedPayload;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
public class OrderCreatedListener {

    private final JsonMapper jsonMapper;
    private final OrderCreatedHandler handler;

    public OrderCreatedListener(JsonMapper jsonMapper, OrderCreatedHandler handler) {
        this.jsonMapper = jsonMapper;
        this.handler = handler;
    }

    // Own consumer group: inventory reads the topic independently of any other service.
    @KafkaListener(topics = KafkaTopicsConfig.ORDER_CREATED, groupId = "inventory-service")
    public void onMessage(ConsumerRecord<String, String> record) {
        // The TypeReference matters: without it, Jackson would turn the generic payload into a plain Map.
        EventEnvelope<OrderCreatedPayload> event = jsonMapper.readValue(
                record.value(), new TypeReference<EventEnvelope<OrderCreatedPayload>>() {});
        handler.handle(record.key(), record.partition(), event);
    }
}