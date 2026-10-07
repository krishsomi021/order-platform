package com.krishna.order_platform.inventory.messaging;

import com.krishna.order_platform.events.EventEnvelope;
import com.krishna.order_platform.events.OrderCreatedPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OrderCreatedHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderCreatedHandler.class);

    // Phase 4 replaces this body with the inventory reservation. For now it only proves delivery.
    public void handle(String key, int partition, EventEnvelope<OrderCreatedPayload> event) {
        log.info("Received {} eventId={} orderId={} key={} partition={}",
                event.eventType(), event.eventId(), event.orderId(), key, partition);
    }
}