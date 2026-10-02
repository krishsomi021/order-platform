package com.krishna.order_platform.order.repo;

import com.krishna.order_platform.order.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {
}