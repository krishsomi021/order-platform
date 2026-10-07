package com.krishna.order_platform.outbox;

import com.krishna.order_platform.TestcontainersConfiguration;
import com.krishna.order_platform.config.KafkaTopicsConfig;
import com.krishna.order_platform.events.OrderEvents;
import com.krishna.order_platform.order.api.CreateOrderRequest;
import com.krishna.order_platform.order.api.OrderResponse;
import com.krishna.order_platform.order.domain.Order;
import com.krishna.order_platform.order.domain.OrderItem;
import com.krishna.order_platform.order.repo.OrderRepository;
import com.krishna.order_platform.order.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "outbox.publisher.enabled=false")
@Import(TestcontainersConfiguration.class)
class OutboxWriteIntegrationTest {

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired OutboxRepository outboxRepository;
    @Autowired OutboxWriter outboxWriter;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JsonMapper jsonMapper;

    @AfterEach
    void cleanUp() {
        outboxRepository.deleteAll();
        orderRepository.deleteAll();
    }

    private static CreateOrderRequest request() {
        return new CreateOrderRequest(UUID.randomUUID(), List.of(
                new CreateOrderRequest.Item("SKU-1", 2, new BigDecimal("19.99"))));
    }

    private List<OutboxEvent> rowsFor(UUID orderId) {
        return outboxRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(orderId))
                .toList();
    }

    @Test
    void orderAndOutboxRowCommitTogether() {
        OrderResponse created = orderService.create(request());

        assertThat(orderRepository.existsById(created.id())).isTrue();
        List<OutboxEvent> rows = rowsFor(created.id());
        assertThat(rows).hasSize(1);

        OutboxEvent row = rows.get(0);
        assertThat(row.getTopic()).isEqualTo(KafkaTopicsConfig.ORDER_CREATED);
        assertThat(row.getEventType()).isEqualTo("OrderCreated");
        assertThat(row.getAggregateType()).isEqualTo(OrderEvents.AGGREGATE_TYPE);
        assertThat(row.getPublishedAt()).isNull();

        Map<?, ?> json = jsonMapper.readValue(row.getPayload(), Map.class);
        assertThat(json.get("eventId")).isEqualTo(row.getEventId().toString());
        assertThat(json.get("orderId")).isEqualTo(created.id().toString());
        assertThat(json.get("schemaVersion")).isEqualTo(1);
        Map<?, ?> payload = (Map<?, ?>) json.get("payload");
        assertThat(payload.get("totalAmount")).isEqualTo("39.98");
    }

    @Test
    void rolledBackOuterTransactionLeavesNeitherRow() {
        AtomicReference<UUID> orderId = new AtomicReference<>();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            // orderService.create joins this outer transaction (REQUIRED), and so does the outbox append
            orderId.set(orderService.create(request()).id());
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(orderId.get()).isNotNull();
        assertThat(orderRepository.existsById(orderId.get())).isFalse();
        assertThat(rowsFor(orderId.get())).isEmpty();
    }

    @Test
    void appendOutsideTransactionThrows() {
        Order order = Order.create(UUID.randomUUID(),
                List.of(new OrderItem("SKU-1", 1, new BigDecimal("10.00"))));

        assertThatThrownBy(() -> outboxWriter.append(
                OrderEvents.AGGREGATE_TYPE, KafkaTopicsConfig.ORDER_CREATED, OrderEvents.orderCreated(order)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}