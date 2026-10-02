package com.krishna.order_platform.order.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CreateOrderRequest(
        @NotNull UUID customerId,
        @NotEmpty List<@Valid Item> items) {

    public record Item(
            @NotBlank String sku,
            @Positive @Max(10_000) int quantity,
            @NotNull @DecimalMin("0.01") @Digits(integer = 7, fraction = 2) BigDecimal unitPrice) {}
}