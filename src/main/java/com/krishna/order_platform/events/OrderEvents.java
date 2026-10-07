package com.krishna.order_platform.events;

import com.krishna.order_platform.order.domain.Order;

import java.util.List;
import java.util.UUID;

public final class OrderEvents {

    public static final String AGGREGATE_TYPE = "Order";
    public static final int SCHEMA_VERSION = 1;

    private OrderEvents() {
    }

    public static EventEnvelope<OrderCreatedPayload> orderCreated(Order order) {
        List<OrderCreatedPayload.Item> items = order.getItems().stream()
                .map(i -> new OrderCreatedPayload.Item(
                        i.getSku(), i.getQuantity(), i.getUnitPrice().toPlainString()))
                .toList();
        var payload = new OrderCreatedPayload(
                order.getCustomerId(), order.getTotalAmount().toPlainString(), items);
        // eventId is created once, here. Publisher retries reuse the stored row, so the id never changes.
        return new EventEnvelope<>(UUID.randomUUID(), "OrderCreated", SCHEMA_VERSION,
                order.getId(), order.getCreatedAt(), payload);
    }
}