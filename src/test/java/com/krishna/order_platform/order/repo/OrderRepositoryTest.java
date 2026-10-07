package com.krishna.order_platform.order.repo;

import com.krishna.order_platform.TestcontainersConfiguration;
import com.krishna.order_platform.order.domain.Order;
import com.krishna.order_platform.order.domain.OrderItem;
import com.krishna.order_platform.order.domain.OrderStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class OrderRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static final UUID CUSTOMER = UUID.randomUUID();

    @Autowired OrderRepository orderRepository;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
    }

    // Tests that commit for real (NOT_SUPPORTED) leave rows behind; clean up after every test.
    @AfterEach
    void cleanUp() {
        // REQUIRES_NEW suspends any (possibly aborted) test transaction and runs in a clean one
        TransactionTemplate cleanup = new TransactionTemplate(txManager);
        cleanup.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        cleanup.executeWithoutResult(s -> {
            jdbc.update("delete from order_items");
            jdbc.update("delete from orders");
        });
    }

    // ---------- infrastructure ----------

    @Test
    void flywayMigratesFreshDatabaseAndEntitiesValidateAgainstIt() {
        Integer failed = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where not success", Integer.class);
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class);

        assertThat(failed).isZero();
        assertThat(applied).isGreaterThanOrEqualTo(1);
        assertThat(orderRepository.count()).isZero();
    }

    // ---------- mapping ----------

    @Test
    void savedOrderWithItemsIsReloadedFromTheDatabase() {
        Order order = Order.create(CUSTOMER, List.of(
                new OrderItem("SKU-1", 2, new BigDecimal("19.99")),
                new OrderItem("SKU-2", 1, new BigDecimal("5.50"))));

        orderRepository.saveAndFlush(order);
        em.clear(); // drop the first-level cache so findById really hits the database

        Order loaded = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(loaded.getCustomerId()).isEqualTo(CUSTOMER);
        assertThat(loaded.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(loaded.getTotalAmount()).isEqualByComparingTo("45.48");
        assertThat(loaded.getItems()).extracting(OrderItem::getSku)
                .containsExactlyInAnyOrder("SKU-1", "SKU-2");
    }

    // Pins Order.MAX_TOTAL to the real column: if a migration ever changes the precision
    // of total_amount, this fails instead of the constant silently drifting.
    @Test
    void persistsMaximumTotal() {
        Order order = Order.create(CUSTOMER, List.of(new OrderItem("SKU-1", 1, Order.MAX_TOTAL)));

        orderRepository.saveAndFlush(order);
        em.clear();

        Order loaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(loaded.getTotalAmount()).isEqualByComparingTo(Order.MAX_TOTAL);
    }

    // ---------- constraints: the database says no ----------
    // These are CHECK constraints, which Hibernate cannot pre-check, so only Postgres can reject them.

    @Test
    void databaseRejectsZeroQuantity() {
        Order order = Order.create(CUSTOMER, List.of(new OrderItem("SKU-1", 0, new BigDecimal("1.00"))));

        assertThatThrownBy(() -> orderRepository.saveAndFlush(order))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("ck_order_items_quantity");
    }

    @Test
    void databaseRejectsZeroUnitPrice() {
        Order order = Order.create(CUSTOMER, List.of(new OrderItem("SKU-1", 1, new BigDecimal("0.00"))));

        assertThatThrownBy(() -> orderRepository.saveAndFlush(order))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("ck_order_items_unit_price");
    }

    @Test
    void databaseRejectsUnknownStatusEvenWhenTheEntityIsBypassed() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into orders (id, customer_id, status, total_amount, created_at, updated_at, version) "
                        + "values (?, ?, 'BOGUS', 1.00, now(), now(), 0)", UUID.randomUUID(), CUSTOMER))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("ck_orders_status");
    }

    @Test
    void databaseRejectsAnItemWithoutAParentOrder() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into order_items (order_id, sku, quantity, unit_price) values (?, 'SKU-1', 1, 1.00)",
                UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("fk_order_items_order");
    }

    // ---------- optimistic locking ----------

    @Test
    void versionIncrementsWhenAnOrderChanges() {
        Order order = orderRepository.saveAndFlush(newOrder());
        long before = versionOf(order.getId());

        order.transitionTo(OrderStatus.PAYMENT_PENDING, NOW);
        orderRepository.flush();

        assertThat(versionOf(order.getId())).isEqualTo(before + 1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // need real, separate transactions
    void staleUpdateFailsWithOptimisticLockingException() {
        Order order = newOrder();
        tx.executeWithoutResult(s -> orderRepository.saveAndFlush(order));
        UUID id = order.getId();

        // two "requests" load the same order, each in its own transaction
        Order first = tx.execute(s -> orderRepository.findById(id).orElseThrow());
        Order second = tx.execute(s -> orderRepository.findById(id).orElseThrow());

        first.transitionTo(OrderStatus.PAYMENT_PENDING, NOW);
        tx.executeWithoutResult(s -> orderRepository.saveAndFlush(first)); // wins: version 0 -> 1

        second.transitionTo(OrderStatus.CANCELLED, NOW); // based on the stale version 0
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> orderRepository.saveAndFlush(second)))
                .isInstanceOf(OptimisticLockingFailureException.class);

        Order result = orderRepository.findById(id).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING); // first writer kept
        assertThat(versionOf(id)).isEqualTo(1);
    }

    // ---------- transactions ----------

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // @DataJpaTest's own tx would hide a real rollback
    void rolledBackTransactionLeavesNothingPersisted() {
        Order order = newOrder();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            orderRepository.saveAndFlush(order);
            assertThat(orderRepository.existsById(order.getId())).isTrue(); // visible inside the tx
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(orderRepository.existsById(order.getId())).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from order_items", Integer.class)).isZero();
    }

    @Test
    void findByCustomerIdReturnsOnlyThatCustomersOrdersNewestFirstWithPaging() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        List<Order> mine = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Order o = orderRepository.saveAndFlush(newOrder());
            // pin createdAt so ordering is deterministic (no reliance on Instant.now() ties)
            jdbc.update("update orders set created_at = ? where id = ?",
                    Timestamp.from(base.plusSeconds(i)), o.getId());
            mine.add(o);
        }
        orderRepository.saveAndFlush(Order.create(UUID.randomUUID(),
                List.of(new OrderItem("OTHER", 1, new BigDecimal("1.00")))));
        em.clear();

        Sort newestFirst = Sort.by(Sort.Direction.DESC, "createdAt");

        Page<Order> first = orderRepository.findByCustomerId(CUSTOMER, PageRequest.of(0, 2, newestFirst));
        assertThat(first.getTotalElements()).isEqualTo(5);
        assertThat(first.getTotalPages()).isEqualTo(3);
        assertThat(first.getContent()).extracting(Order::getId)
                .containsExactly(mine.get(4).getId(), mine.get(3).getId());

        Page<Order> last = orderRepository.findByCustomerId(CUSTOMER, PageRequest.of(2, 2, newestFirst));
        assertThat(last.getContent()).extracting(Order::getId)
                .containsExactly(mine.get(0).getId());
    }

    // ---------- helpers ----------

    private Order newOrder() {
        return Order.create(CUSTOMER, List.of(new OrderItem("SKU-1", 2, new BigDecimal("19.99"))));
    }

    private long versionOf(UUID id) {
        return jdbc.queryForObject("select version from orders where id = ?", Long.class, id);
    }
}