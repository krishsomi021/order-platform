package com.krishna.order_platform.order.api;

import com.krishna.order_platform.order.domain.OrderStatus;
import com.krishna.order_platform.order.service.OrderNotFoundException;
import com.krishna.order_platform.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean OrderService orderService;

    private static final String VALID_BODY = """
            {"customerId":"3f2b8c1e-6a52-4c0e-9d51-0c6f1c1a7a11",
             "items":[{"sku":"SKU-1","quantity":2,"unitPrice":19.99}]}""";

    @Test
    void validPostReturns201WithLocation() throws Exception {
        UUID id = UUID.randomUUID();
        when(orderService.create(any())).thenReturn(new OrderResponse(
                id, UUID.randomUUID(), OrderStatus.CREATED, new BigDecimal("39.98"),
                Instant.now(), List.of(new OrderResponse.ItemResponse("SKU-1", 2, new BigDecimal("19.99")))));

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/orders/" + id))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void emptyItemsReturns400() throws Exception {
        String body = "{\"customerId\":\"3f2b8c1e-6a52-4c0e-9d51-0c6f1c1a7a11\",\"items\":[]}";
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void negativeQuantityReturns400() throws Exception {
        String body = """
                {"customerId":"3f2b8c1e-6a52-4c0e-9d51-0c6f1c1a7a11",
                 "items":[{"sku":"SKU-1","quantity":-1,"unitPrice":19.99}]}""";
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownOrderReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(orderService.get(id)).thenThrow(new OrderNotFoundException(id));

        mockMvc.perform(get("/api/orders/" + id))
                .andExpect(status().isNotFound());
    }
}