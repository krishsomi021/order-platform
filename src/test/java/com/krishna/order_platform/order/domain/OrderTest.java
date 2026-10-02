package com.krishna.order_platform.order.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OrderTest {

    @Test
    void newOrderStartsCreatedWithCorrectTotal() {
        Order order = Order.create(UUID.randomUUID(), List.of(
                new OrderItem("A", 3, new BigDecimal("19.99")),
                new OrderItem("B", 1, new BigDecimal("5.00"))));

        assertEquals(OrderStatus.CREATED, order.getStatus());
        assertEquals(0, new BigDecimal("64.97").compareTo(order.getTotalAmount()));
    }

    @Test
    void validTransitionChangesStatus() {
        Order order = Order.create(UUID.randomUUID(), List.of(new OrderItem("A", 1, BigDecimal.ONE)));
        order.transitionTo(OrderStatus.PAYMENT_PENDING);
        assertEquals(OrderStatus.PAYMENT_PENDING, order.getStatus());
    }

    @Test
    void invalidTransitionThrows() {
        Order order = Order.create(UUID.randomUUID(), List.of(new OrderItem("A", 1, BigDecimal.ONE)));
        assertThrows(InvalidStateTransitionException.class,
                () -> order.transitionTo(OrderStatus.CONFIRMED));
    }
}