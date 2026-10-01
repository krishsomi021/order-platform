package com.krishna.order_platform.order.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class OrderStatusTest {

    @Test
    void happyPathIsAllowed() {
        assertTrue(OrderStatus.CREATED.canTransitionTo(OrderStatus.PAYMENT_PENDING));
        assertTrue(OrderStatus.PAYMENT_PENDING.canTransitionTo(OrderStatus.PAID));
        assertTrue(OrderStatus.PAID.canTransitionTo(OrderStatus.INVENTORY_PENDING));
        assertTrue(OrderStatus.INVENTORY_PENDING.canTransitionTo(OrderStatus.CONFIRMED));
    }

    @Test
    void cannotSkipSteps() {
        assertFalse(OrderStatus.CREATED.canTransitionTo(OrderStatus.CONFIRMED));
        assertFalse(OrderStatus.CREATED.canTransitionTo(OrderStatus.PAID));
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CONFIRMED", "CANCELLED"})
    void terminalStatesAllowNoTransitions(OrderStatus terminal) {
        for (OrderStatus next : OrderStatus.values()) {
            assertFalse(terminal.canTransitionTo(next));
        }
    }
}