package com.krishna.order_platform.order.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void createWithNoItemsThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> Order.create(UUID.randomUUID(), List.of()));
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

    @Test
    void rejectsTotalAboveColumnLimit() {
        var item = new OrderItem("SKU-1", 10_000, new BigDecimal("1000000.00"));
        assertThatThrownBy(() -> Order.create(UUID.randomUUID(), List.of(item)))
                .isInstanceOf(OrderLimitExceededException.class);
    }

    @Test
    void acceptsTotalExactlyAtLimit() {
        var item = new OrderItem("SKU-1", 1, Order.MAX_TOTAL);
        Order order = Order.create(UUID.randomUUID(), List.of(item));
        assertThat(order.getTotalAmount()).isEqualByComparingTo(Order.MAX_TOTAL);
    }
}