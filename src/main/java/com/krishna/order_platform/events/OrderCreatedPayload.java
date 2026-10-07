package com.krishna.order_platform.events;

import java.util.List;
import java.util.UUID;

// Money travels as strings so BigDecimal values stay exact on the wire.
public record OrderCreatedPayload(UUID customerId, String totalAmount, List<Item> items) {

    public record Item(String sku, int quantity, String unitPrice) {
    }
}