package com.krishna.order_platform.inventory.messaging;

import com.krishna.order_platform.TestcontainersConfiguration;
import com.krishna.order_platform.order.api.CreateOrderRequest;
import com.krishna.order_platform.order.repo.OrderRepository;
import com.krishna.order_platform.order.service.OrderService;
import com.krishna.order_platform.outbox.OutboxEvent;
import com.krishna.order_platform.outbox.OutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

// Publisher is enabled (default) and the real Kafka container runs: this exercises the whole pipeline.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderCreatedFlowIntegrationTest {

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired OutboxRepository outboxRepository;

    // Wraps the real bean: the listener still calls it, and we can verify the calls.
    @MockitoSpyBean OrderCreatedHandler handler;

    @AfterEach
    void cleanUp() {
        outboxRepository.deleteAll();
        orderRepository.deleteAll();
    }

    private static CreateOrderRequest request() {
        return new CreateOrderRequest(UUID.randomUUID(), List.of(
                new CreateOrderRequest.Item("SKU-1", 2, new BigDecimal("19.99"))));
    }

    private OutboxEvent rowFor(UUID orderId) {
        return outboxRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(orderId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void orderCreatedFlowsThroughOutboxAndKafkaToConsumer() {
        UUID orderId = orderService.create(request()).id();

        // The consumer received it, keyed by order id. atLeastOnce: duplicates are legal under at-least-once.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                verify(handler, atLeastOnce()).handle(
                        eq(orderId.toString()),
                        anyInt(),
                        argThat(e -> e.orderId().equals(orderId) && "OrderCreated".equals(e.eventType()))));

        // Marked published. Separate await: the consumer can see the message just before the publisher commits.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(rowFor(orderId).getPublishedAt()).isNotNull());
    }

    // Optional. Shows keys spread over the 3 partitions. It does not prove per-key ordering:
    // each order has exactly one event until Phase 4 adds more.
    @Test
    void tenOrdersUseMoreThanOnePartition() {
        Set<String> orderIds = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            orderIds.add(orderService.create(request()).id().toString());
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Integer> partitions = ArgumentCaptor.forClass(Integer.class);
            verify(handler, atLeast(10)).handle(keys.capture(), partitions.capture(), any());

            assertThat(new HashSet<>(keys.getAllValues())).containsAll(orderIds);
            Set<Integer> used = IntStream.range(0, keys.getAllValues().size())
                    .filter(i -> orderIds.contains(keys.getAllValues().get(i)))
                    .mapToObj(i -> partitions.getAllValues().get(i))
                    .collect(Collectors.toSet());
            assertThat(used).hasSizeGreaterThan(1);
        });
    }
}