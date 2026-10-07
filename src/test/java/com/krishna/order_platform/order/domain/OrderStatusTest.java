package com.krishna.order_platform.order.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static com.krishna.order_platform.order.domain.OrderStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            CREATED, Set.of(PAYMENT_PENDING, CANCELLED),
            PAYMENT_PENDING, Set.of(CONFIRMED, CANCELLED),
            CONFIRMED, Set.of(),
            CANCELLED, Set.of());

    @Test
    void transitionTableIsExactlyAsDesigned() {
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(ALLOWED.get(from).contains(to));
            }
        }
    }
}