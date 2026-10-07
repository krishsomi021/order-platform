# Event-Driven Order & Inventory Platform

A backend system built with **Java 21, Spring Boot, PostgreSQL, and Kafka**. The goal is to model a realistic order-processing backend: an order API, a strict order lifecycle, a schema that enforces its own rules, and asynchronous workflows that stay correct under duplicate events, transient failures, and concurrency.

> **Status:** Phases 1-3 complete: order API, versioned migrations, database constraints, and a transactional outbox that publishes `order.created` to Kafka, consumed by a stub in the inventory consumer group. Tested against real PostgreSQL and Kafka via Testcontainers. Inventory reservation, payment, idempotency, and observability are planned. See [Roadmap](#roadmap).

## Architecture

```
Client -> Order API -> PostgreSQL (order row + outbox row, one transaction)
                              |
                      Outbox publisher (polls unpublished rows, in id order)
                              |
                              v
                      Kafka: order.created (3 partitions, key = orderId)
                              |
                              v
                      Inventory consumer, group inventory-service   [built: logs only]
                              |
                              +-> planned: InventoryReserved / InventoryRejected
                                      -> Payment -> PaymentCompleted / PaymentFailed
                                      -> Order confirmed or cancelled
```

Built today: the Order API, PostgreSQL, the outbox, the publisher, the `order.created` topic, and a consumer stub. Inventory and payment will be packages in the same deployable (a modular monolith) that talk only through Kafka, each with its own consumer group.

### Events

| Event | Topic | Producer | Consumer group(s) | Status |
|---|---|---|---|---|
| `OrderCreated` | `order.created` | order | `inventory-service` | Built (consumer logs only) |
| `InventoryReserved` / `InventoryRejected` | `inventory.events` | inventory | `order-service`, `payment-service` (Reserved only) | Planned |
| `PaymentCompleted` / `PaymentFailed` | `payment.events` | payment | `order-service`, `inventory-service` (Failed releases stock) | Planned |
| `InventoryReleased` | `inventory.events` | inventory | `order-service` | Planned |

Every event has a stable `eventId`, and `orderId` is the partition key so one order's events stay in order.

## Tech stack

| Area | Technology |
|---|---|
| Language / framework | Java 21, Spring Boot 4.1.1, Spring Web, Spring Data JPA |
| Database | PostgreSQL 16 (Docker Compose), Flyway migrations |
| Messaging | Apache Kafka 4.3 (Docker Compose), Spring Kafka (kafka-clients 4.2.1), transactional outbox |
| Build | Maven (wrapper included) |
| Testing | JUnit 5, Mockito, Spring MockMvc, Testcontainers (PostgreSQL, Kafka), AssertJ, Awaitility |
| Planned | WireMock, Actuator + Prometheus, k6, GitHub Actions |

Redis is deliberately not used: nothing here needs it, and the one candidate (an idempotency-key store) must commit atomically with the order insert, which only a PostgreSQL unique constraint can do.

## What works today (Phases 1-3)

- `POST /api/orders` creates an order (returns `201 Created` with a `Location` header)
- `GET /api/orders/{id}` fetches an order (`200` or `404`)
- Request validation with RFC 7807 `ProblemDetail` error bodies (`400`, `404`, `409`)
- Orders and items persisted in PostgreSQL, with the schema owned by Flyway migrations
- Database-level constraints (`CHECK`, foreign key, `NOT NULL`) as a second line of defense behind application validation
- Optimistic locking (`@Version`) with concurrent-modification conflicts mapped to `409`
- A paginated repository query, `findByCustomerId`, backed by a composite index (repository-level only; not exposed over HTTP yet)
- An order lifecycle state machine enforced in the domain model
- An order whose total exceeds the column limit (`NUMERIC(12,2)`) returns `400`, not a database error
- Transactional outbox: creating an order writes the order and an `OrderCreated` outbox row in one transaction (never a Kafka call inside it)
- An outbox publisher sends pending rows to Kafka `order.created` (3 partitions, key = order id), in id order, stopping at the first failed send
- A consumer stub in group `inventory-service` receives and logs each event
- 42 automated tests, including an end-to-end test (order, outbox, Kafka, consumer) against real PostgreSQL and Kafka containers

### Order lifecycle

```
CREATED -> PAYMENT_PENDING -> CONFIRMED

CREATED / PAYMENT_PENDING -> CANCELLED
CONFIRMED and CANCELLED are terminal.
```

The lifecycle is **reserve inventory first, then charge**. `CREATED` means "waiting for the inventory decision"; `InventoryReserved` moves the order to `PAYMENT_PENDING`; `PaymentCompleted` confirms it. A failed payment is compensated by releasing stock, a local `UPDATE`. The earlier pay-then-reserve order needed a refund flow, so `PAID` and `INVENTORY_PENDING` were removed.

## Database and migrations

The schema is owned by [Flyway](https://flywaydb.org/) migrations in `src/main/resources/db/migration/`. Hibernate runs with `ddl-auto: validate`, so the application refuses to start if an entity and the schema disagree.

| Migration | Purpose |
|---|---|
| `V1__create_orders.sql` | `orders` and `order_items` tables, constraints, initial indexes |
| `V2__orders_customer_created_index.sql` | Composite index `(customer_id, created_at DESC)` replacing the single-column customer index |
| `V3__reserve_then_pay_lifecycle.sql` | Re-creates `ck_orders_status` to allow only `CREATED`, `PAYMENT_PENDING`, `CONFIRMED`, `CANCELLED` |
| `V4__create_outbox.sql` | `outbox` table, unique `event_id`, partial index on unpublished rows |

**Never edit an applied migration.** Flyway checksums every applied file, so changes go into a new `V<n>` file.

### Constraints enforced by the database

| Constraint | Rule |
|---|---|
| `ck_orders_status` | status must be one of the four `OrderStatus` values |
| `ck_orders_total_nonnegative` | `total_amount >= 0` |
| `ck_order_items_quantity` | `quantity > 0` |
| `ck_order_items_unit_price` | `unit_price > 0` |
| `fk_order_items_order` | every item references an existing order |
| `uq_outbox_event_id` | each outbox event id is unique |
| `NOT NULL` | on every column the entity marks as required, including `version` |

Indexes: `orders(status)`, `orders(customer_id, created_at DESC)`, `order_items(order_id)`, and `outbox(id) WHERE published_at IS NULL` (a partial index, so polling stays fast as published rows pile up). Postgres does not index foreign keys automatically, so the `order_items(order_id)` index is deliberate.

## Run locally

**Prerequisites:** JDK 21, Docker Desktop. Maven is not required (use the wrapper).

```bash
# 1. Start PostgreSQL (host port 5433 -> container 5432) and Kafka (host port 9092)
docker compose up -d

# 2. Build and run all tests (Docker must be running; see note below)
./mvnw clean verify          # Windows PowerShell: .\mvnw.cmd clean verify

# 3. Run the app on http://localhost:8080 (Flyway migrates the database on startup)
./mvnw spring-boot:run       # Windows PowerShell: .\mvnw.cmd spring-boot:run
```

> **Tests and Docker:** the tests start their own throwaway PostgreSQL and Kafka containers through Testcontainers, so they need Docker running but do **not** need the Compose containers. The Compose containers are only for running the app itself.

> **Resetting the local database:** `docker compose down -v` deletes the data volume. Flyway will rebuild the schema from the migrations on the next start. This is also required if the database was ever created by an older version of the project that used `ddl-auto: update`, because Flyway refuses to adopt a non-empty schema it did not create.

> **Local-dev credentials only.** The database name, user, and password are all `orders` in `docker-compose.yaml` and `application.yml`. They are throwaway defaults for a local container with no real data. Never commit real credentials.

> **Port note:** PostgreSQL is exposed on host port **5433**, not 5432, to avoid clashing with a locally installed PostgreSQL. If you change it, update both `docker-compose.yaml` and the JDBC URL in `application.yml`.

> **Kafka note:** Kafka is exposed on host port **9092** and advertises `localhost:9092`, because the app runs on the host. Topic auto-creation is off; the app declares `order.created` itself on startup.

## API examples

Create an order:

```bash
curl -i -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "3f2b8c1e-6a52-4c0e-9d51-0c6f1c1a7a11",
    "items": [{ "sku": "SKU-1", "quantity": 2, "unitPrice": 19.99 }]
  }'
```

PowerShell equivalent:

```powershell
$body = @{
  customerId = "3f2b8c1e-6a52-4c0e-9d51-0c6f1c1a7a11"
  items = @(@{ sku = "SKU-1"; quantity = 2; unitPrice = 19.99 })
} | ConvertTo-Json -Depth 5

Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/orders -ContentType "application/json" -Body $body
```

Fetch it:

```bash
curl http://localhost:8080/api/orders/<id>
```

Example response:

```json
{
  "id": "64438722-d6f8-4c88-b8ff-fe2c480c80a6",
  "customerId": "3f2b8c1e-6a52-4c0e-9d51-0c6f1c1a7a11",
  "status": "CREATED",
  "totalAmount": 39.98,
  "createdAt": "2026-10-02T16:29:36.893882Z",
  "items": [{ "sku": "SKU-1", "quantity": 2, "unitPrice": 19.99 }]
}
```

### Validation rules

| Field | Rule |
|---|---|
| `customerId` | required UUID |
| `items` | non-empty list |
| `items[].sku` | not blank |
| `items[].quantity` | positive, max 10,000 |
| `items[].unitPrice` | at least 0.01, up to 7 integer digits and 2 decimal places |

### Error responses

| Situation | Status |
|---|---|
| Invalid request body | `400 Bad Request` |
| Order total above 9,999,999,999.99 | `400 Bad Request` |
| Order not found | `404 Not Found` |
| Illegal order state transition | `409 Conflict` |
| Concurrent modification (stale optimistic-lock version) | `409 Conflict` |

## Project structure

```
src/main/java/com/krishna/order_platform/
├── order/
│   ├── api/        CreateOrderRequest, OrderResponse, OrderController, GlobalExceptionHandler
│   ├── service/    OrderService, OrderNotFoundException
│   ├── repo/       OrderRepository
│   └── domain/     Order, OrderItem, OrderStatus, InvalidStateTransitionException, OrderLimitExceededException
├── events/         EventEnvelope, OrderCreatedPayload, OrderEvents
├── outbox/         OutboxEvent, OutboxRepository, OutboxWriter, OutboxPublisher
├── inventory/messaging/   OrderCreatedListener, OrderCreatedHandler
└── config/         KafkaTopicsConfig, SchedulingConfig, TimeConfig

src/main/resources/
├── application.yml
└── db/migration/   V1 ... V4 (see Database and migrations)

src/test/java/com/krishna/order_platform/
├── TestcontainersConfiguration.java      shared PostgreSQL + Kafka containers (@ServiceConnection)
├── OrderPlatformApplicationTests.java
├── order/{api, service, domain, repo}/   test classes mirror the main packages
├── outbox/                               repository, publisher and write tests
└── inventory/messaging/                  listener and end-to-end flow tests
```

Packaged by feature rather than by layer, so future Inventory and Payment features get their own packages.

## Testing

42 tests, all passing.

| Test class | Type | Tests | Covers |
|---|---|---|---|
| `OrderStatusTest` | Unit | 1 | One table-driven test over all 16 from/to transition pairs |
| `OrderTest` | Unit | 7 | Initial status, total calculation, transitions, empty-order rejection, `MAX_TOTAL` limit |
| `OrderServiceTest` | Unit (Mockito) | 3 | Total calculation, save, not-found handling, `OrderCreated` appended to the outbox |
| `OrderControllerTest` | Web layer (`@WebMvcTest`) | 7 | 201 + `Location`, validation failures (400), 404, optimistic-lock conflict (409), order-limit 400 |
| `OrderRepositoryTest` | Persistence (`@DataJpaTest` + Testcontainers) | 12 | See below |
| `OutboxRepositoryTest` | Persistence (`@DataJpaTest` + Testcontainers) | 3 | Locking batch query returns only unpublished rows, oldest first, honours the limit; duplicate `event_id` rejected by `uq_outbox_event_id` |
| `OutboxPublisherTest` | Unit (Mockito) | 2 | All rows marked published when sends succeed; batch stops at the first failed send so later rows never overtake |
| `OutboxWriteIntegrationTest` | Integration (`@SpringBootTest` + Testcontainers) | 3 | Order and outbox row commit together; a rolled-back transaction leaves neither; appending outside a transaction throws |
| `OrderCreatedListenerTest` | Unit (Mockito) | 1 | Envelope parsed from JSON and delegated to the handler |
| `OrderCreatedFlowIntegrationTest` | End-to-end (`@SpringBootTest` + Testcontainers) | 2 | Order flows through outbox and Kafka to the consumer (Awaitility, no sleeps); ten orders use more than one partition |
| `OrderPlatformApplicationTests` | Smoke | 1 | Full Spring context loads against Testcontainers PostgreSQL and Kafka |

`OrderRepositoryTest` covers:

- Flyway migrates an empty database and the entities validate against it
- Save and reload an order with items (first-level cache cleared so the database is really read)
- The database rejects zero quantity, zero unit price, an unknown status (via raw SQL), and an orphan item with no parent order, asserting the **constraint name** that fired
- The database rejects the removed status `PAID` (after V3)
- An order at the maximum total round-trips through the database
- `@Version` increments when an order changes
- A stale update fails with `OptimisticLockingFailureException`, and the first writer's change survives
- A rolled-back transaction leaves nothing persisted
- `findByCustomerId` returns only one customer's orders, newest first, with correct paging

Integration test classes end in `Test`, not `IT`, because Surefire only runs `*Test` names by default; an `*IT` class would be silently skipped.

## Design decisions

1. **Constructor injection only.** Dependencies are explicit and final, which keeps testing simple.
2. **`BigDecimal` for money.** `double` has binary rounding errors. Amounts are stored as `NUMERIC(12,2)`, and tests compare with `compareTo`, not `equals`.
3. **DTOs are separate from entities.** The API contract is decoupled from persistence, and lazy-loaded data never leaks into serialization.
4. **State machine lives in the enum.** Transition rules sit in one place (`OrderStatus.canTransitionTo`), are trivial to unit test, and cannot be bypassed because `Order` has no `setStatus`; all changes go through `transitionTo(OrderStatus next, Instant at)`, which takes the timestamp as a parameter so tests can assert the exact `updatedAt`.
5. **Dedicated `InvalidStateTransitionException`.** It maps to `409`. Reusing a generic `IllegalStateException` would risk mislabeling unrelated framework errors as conflicts.
6. **`@Version` on `Order`.** Because the entity assigns its own UUID, Spring Data otherwise treats every save as an update (an extra `SELECT` plus `merge`). A version field fixes that and provides optimistic locking, which matters once inventory and payment update orders concurrently.
7. **`EnumType.STRING` for status.** Ordinal storage silently corrupts data if the enum is ever reordered.
8. **`spring.jpa.open-in-view: false`.** No hidden lazy loading in the web layer. The service maps entities to response DTOs inside the transaction.
9. **Invariants enforced in the domain, not just the DTO.** `Order.create` rejects a null customer or empty item list, and validation on the request caps price precision so the computed total always matches what the database stores.
10. **Protected no-arg constructor.** Required by JPA, but protected so application code cannot create half-initialized orders.
11. **Flyway migrations with `ddl-auto: validate`.** `ddl-auto: update` guesses changes from entities, never drops columns, and leaves no history. Versioned SQL is reviewable, ordered, and checksummed, and `validate` turns entity/schema drift into a startup failure.
12. **The database is a second line of defense.** Application validation gives friendly errors, but `CHECK` constraints and the foreign key hold even if code bypasses the entity. Tests assert the specific constraint that rejected the data so a failure for the wrong reason cannot pass.
13. **Testcontainers over H2.** H2 accepts SQL that PostgreSQL rejects and differs on types, locking, and constraints. The behavior this project depends on (constraints, `@Version`, later row-level concurrency) is PostgreSQL behavior, so tests run against real PostgreSQL.
14. **Composite index matched to the query.** `findByCustomerId` filters on `customer_id` and sorts by `created_at`, so `(customer_id, created_at DESC)` serves both with no separate sort step, and it also covers lookups by `customer_id` alone.
15. **Paginated queries do not fetch collections.** Fetch-joining `items` while paginating forces Hibernate to page in memory. List queries return orders without items.
16. **Transaction tests opt out of the test transaction.** `@DataJpaTest` rolls back every test by default, which would hide real commit and rollback behavior. The optimistic-locking and rollback tests run with `Propagation.NOT_SUPPORTED` and drive their own transactions, with cleanup in a separate `REQUIRES_NEW` transaction (PostgreSQL aborts an entire transaction after any failed statement).
17. **Reserve inventory, then charge.** Compensating a failed payment is a local stock release. Pay-then-reserve would need a refund flow, and running both in parallel would make the order track every combination of two results.
18. **No Redis.** The one candidate, an idempotency-key store, must commit atomically with the order insert, and a PostgreSQL unique constraint can do that while Redis cannot join the transaction.
19. **`Order.MAX_TOTAL` equals the column maximum.** An oversized order is rejected in the domain with a `400` instead of failing in the database with a `500`.
20. **Transactional outbox, not publish-after-commit and not CDC.** No transaction spans PostgreSQL and Kafka. Sending inside the transaction can publish an event for an order that never committed; sending after commit can lose it. The outbox row commits with the order and a separate publisher sends it. `OutboxWriter` uses `Propagation.MANDATORY`, so appending outside a transaction fails loudly. CDC (Debezium) gives the same guarantee without polling but adds Kafka Connect, which is more than this project needs.
21. **At-least-once delivery.** A crash after the broker acknowledges but before the row is marked published resends the event. The `eventId` is fixed when the row is written and reused on every retry; consumer-side deduplication is Phase 6.
22. **Ordering by key and by row order.** The key is the order id, so one order's events share a partition. The publisher reads by identity `id` with plain `FOR UPDATE` (not `SKIP LOCKED`, which could let a second instance send a later event first) and stops the batch at the first failed send.
23. **Payload stored as `TEXT`, sent unchanged.** The published bytes equal the stored bytes, with no JSON column mapping. Money travels as strings so `BigDecimal` stays exact.
24. **Auto topic creation off.** The topic is a declared bean with 3 partitions, so a typo fails loudly instead of silently creating a 1-partition topic.

## Verified behavior (live check)

Run against the Compose PostgreSQL and Kafka on a local machine. These are single observations, not benchmarks.

- **Happy path:** an order's outbox row was marked published about 0.9 s after it was created. The topic has 3 partitions (replication factor 1), the message key is the order id, and the `inventory-service` group showed lag 0.
- **Kafka outage:** with the broker stopped, `POST /api/orders` still returned `201` and the outbox row stayed unpublished. The publisher logged a failure about every 5.5 s (four consecutive attempts observed), each for the same `eventId`. After the broker restarted, the row was published with no manual step and the consumer received the same `eventId`.

## Known limitations

- `POST /api/orders` is not idempotent; a client retry creates a duplicate order.
- Delivery is at-least-once, and consumers do not deduplicate yet (Phase 6).
- The consumer only logs. A message that fails to parse is retried by the default error handler and then skipped; the dead-letter topic comes in Phase 7.
- The publisher holds a database transaction open while it sends. During an outage that is about 5 s per failed attempt; the batch size and timeouts bound it.
- `Order.create` still calls `Instant.now()` directly; a `Clock` is available (`TimeConfig`) but not yet used there.
- `findByCustomerId` is not exposed over HTTP; there is no list endpoint yet.
- Validation errors return a generic `detail` ("Invalid request content.") without per-field messages.
- `OrderService` still depends on API types (`CreateOrderRequest`, `OrderResponse`); a command object in and a service-level view out would be cleaner.

## Roadmap

| Phase | Scope | Status |
|---|---|---|
| 1 | Java 21, Spring Boot, Maven, REST API, PostgreSQL, basic tests | Done |
| 2 | Flyway migrations, constraints, transactions, repository tests, Testcontainers PostgreSQL | Done |
| 3 | Transactional outbox, Kafka producer/consumer, `OrderCreated`, partition key, consumer group, Testcontainers Kafka | Done |
| 4 | Inventory service, reservation, concurrency protection, `InventoryReserved/Rejected`, concurrency test | Next |
| 5 | Payment workflow, WireMock, retries, timeouts, failure events | Planned |
| 6 | Event IDs and idempotent consumers, duplicate-event integration test | Planned |
| 7 | Retry policy and dead-letter topic | Planned |
| 8 | Actuator + Prometheus metrics | Planned |
| 9 | k6 load testing with real, measured results | Planned |
| 10 | Docker/CI cleanup, then optional Terraform/AWS | Planned |

No performance numbers are claimed until they are actually measured in Phase 9.