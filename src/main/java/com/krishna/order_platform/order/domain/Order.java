package com.krishna.order_platform.order.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders") // "order" is a reserved SQL word
public class Order {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, updatable=false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id", nullable = false)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {} // required by JPA

    // Must equal the max of the total_amount column, NUMERIC(12,2).
    public static final BigDecimal MAX_TOTAL = new BigDecimal("9999999999.99");

    public static Order create(UUID customerId, List<OrderItem> items) {
        if (customerId == null) throw new IllegalArgumentException("customerId is required");
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("Order must have at least one item");
        Order o = new Order();
        o.id = UUID.randomUUID();
        o.customerId = customerId;
        o.status = OrderStatus.CREATED;
        o.items.addAll(items);
        o.totalAmount = items.stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (o.totalAmount.compareTo(MAX_TOTAL) > 0) {
            throw new OrderLimitExceededException(
                    "Order total " + o.totalAmount.toPlainString()
                            + " exceeds the maximum of " + MAX_TOTAL.toPlainString());
        }
        o.createdAt = Instant.now();
        o.updatedAt = o.createdAt;
        return o;
    }

    public void transitionTo(OrderStatus next, Instant at) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidStateTransitionException("Cannot move order from " + status + " to " + next);
        }
        this.status = next;
        this.updatedAt = at;
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public OrderStatus getStatus() { return status; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<OrderItem> getItems() { return List.copyOf(items); }
}