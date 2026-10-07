package com.krishna.order_platform.order.domain;

public enum OrderStatus {
    CREATED, PAYMENT_PENDING, CONFIRMED, CANCELLED;

    public boolean canTransitionTo(OrderStatus next) {
        return switch (this) {
            case CREATED         -> next == PAYMENT_PENDING || next == CANCELLED;
            case PAYMENT_PENDING -> next == CONFIRMED || next == CANCELLED;
            case CONFIRMED, CANCELLED -> false;
        };
    }
}