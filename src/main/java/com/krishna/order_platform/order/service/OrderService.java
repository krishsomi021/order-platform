package com.krishna.order_platform.order.service;

import com.krishna.order_platform.config.KafkaTopicsConfig;
import com.krishna.order_platform.events.OrderEvents;
import com.krishna.order_platform.order.api.CreateOrderRequest;
import com.krishna.order_platform.order.api.OrderResponse;
import com.krishna.order_platform.order.domain.Order;
import com.krishna.order_platform.order.domain.OrderItem;
import com.krishna.order_platform.order.repo.OrderRepository;
import com.krishna.order_platform.outbox.OutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OutboxWriter outboxWriter;

    public OrderService(OrderRepository orderRepository, OutboxWriter outboxWriter) {
        this.orderRepository = orderRepository;
        this.outboxWriter = outboxWriter;
    }

    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        List<OrderItem> items = request.items().stream()
                .map(i -> new OrderItem(i.sku(), i.quantity(), i.unitPrice()))
                .toList();
        Order order = Order.create(request.customerId(), items);
        Order saved = orderRepository.save(order);
        outboxWriter.append(OrderEvents.AGGREGATE_TYPE, KafkaTopicsConfig.ORDER_CREATED,
                OrderEvents.orderCreated(saved));
        return OrderResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        return OrderResponse.from(order);
    }
}