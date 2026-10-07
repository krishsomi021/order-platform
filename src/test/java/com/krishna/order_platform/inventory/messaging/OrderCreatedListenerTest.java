package com.krishna.order_platform.inventory.messaging;

import com.krishna.order_platform.events.EventEnvelope;
import com.krishna.order_platform.events.OrderCreatedPayload;
import com.krishna.order_platform.events.OrderEvents;
import com.krishna.order_platform.order.domain.Order;
import com.krishna.order_platform.order.domain.OrderItem;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderCreatedListenerTest {

    @Mock OrderCreatedHandler handler;
    @Captor ArgumentCaptor<EventEnvelope<OrderCreatedPayload>> eventCaptor;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void parsesEnvelopeAndDelegatesToHandler() {
        Order order = Order.create(UUID.randomUUID(),
                List.of(new OrderItem("SKU-1", 2, new BigDecimal("19.99"))));
        String json = jsonMapper.writeValueAsString(OrderEvents.orderCreated(order));
        var record = new ConsumerRecord<>("order.created", 2, 0L, order.getId().toString(), json);

        new OrderCreatedListener(jsonMapper, handler).onMessage(record);

        verify(handler).handle(eq(order.getId().toString()), eq(2), eventCaptor.capture());
        EventEnvelope<OrderCreatedPayload> event = eventCaptor.getValue();
        assertThat(event.orderId()).isEqualTo(order.getId());
        assertThat(event.eventType()).isEqualTo("OrderCreated");
        assertThat(event.payload().totalAmount()).isEqualTo("39.98");
        assertThat(event.payload().items()).hasSize(1);
        assertThat(event.payload().items().get(0).sku()).isEqualTo("SKU-1");
    }
}