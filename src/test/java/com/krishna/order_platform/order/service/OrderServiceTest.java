package com.krishna.order_platform.order.service;

import com.krishna.order_platform.order.api.CreateOrderRequest;
import com.krishna.order_platform.order.api.OrderResponse;
import com.krishna.order_platform.order.domain.Order;
import com.krishna.order_platform.order.domain.OrderStatus;
import com.krishna.order_platform.order.repo.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock OrderRepository orderRepository;
    @InjectMocks OrderService orderService;

    @Test
    void createCalculatesTotalAndSavesOnce() {
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new CreateOrderRequest(UUID.randomUUID(), List.of(
                new CreateOrderRequest.Item("A", 3, new BigDecimal("19.99")),
                new CreateOrderRequest.Item("B", 1, new BigDecimal("5.00"))));

        OrderResponse response = orderService.create(request);

        assertEquals(OrderStatus.CREATED, response.status());
        assertEquals(0, new BigDecimal("64.97").compareTo(response.totalAmount()));
        verify(orderRepository, times(1)).save(any(Order.class));
    }

    @Test
    void getUnknownOrderThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> orderService.get(id));
    }
}