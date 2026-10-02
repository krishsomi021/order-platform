package com.krishna.order_platform.order.api;

import com.krishna.order_platform.order.domain.Order;
import com.krishna.order_platform.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        UUID customerId,
        OrderStatus status,
        BigDecimal totalAmount,
        Instant createdAt,
        List<ItemResponse> items) {

    public record ItemResponse(String sku, int quantity, BigDecimal unitPrice) {}

    public static OrderResponse from(Order o) {
        return new OrderResponse(
                o.getId(), o.getCustomerId(), o.getStatus(), o.getTotalAmount(), o.getCreatedAt(),
                o.getItems().stream()
                        .map(i -> new ItemResponse(i.getSku(), i.getQuantity(), i.getUnitPrice()))
                        .toList());
    }
}