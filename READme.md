# Event-Driven Order & Inventory Platform

A backend system built with **Java 21, Spring Boot, PostgreSQL, and (in later phases) Kafka**. The goal is to model a realistic order-processing backend: an order API, a strict order lifecycle, a schema that enforces its own rules, and, as the project grows, asynchronous payment and inventory workflows that stay correct under duplicate events, transient failures, and concurrency.

> **Status:** Phases 1-2 complete (order API, versioned migrations, database constraints, repository tests on real PostgreSQL via Testcontainers). Kafka, inventory, payment, idempotency, and observability are planned. See [Roadmap](#roadmap).

## Target architecture

```
Client -> Order API (Spring Boot) -> PostgreSQL
                  |
                  +-> Kafka: order.created
                          |-> Payment Service   -> PaymentCompleted / PaymentFailed
                          |-> Inventory Service -> InventoryReserved / InventoryRejected
                                  |
                                  +-> Order confirmed or cancelled
```

Only the **Order API and PostgreSQL** pieces exist today. Everything downstream of Kafka is planned work.

## Tech stack

| Area | Technology |
|---|---|
| Language / framework | Java 21, Spring Boot 4.1.1, Spring Web, Spring Data JPA |
| Database | PostgreSQL 16 (Docker Compose), Flyway migrations |
| Build | Maven (wrapper included) |
| Testing | JUnit 5, Mockito, Spring MockMvc, Testcontainers (PostgreSQL), AssertJ |
| Planned | Kafka (+ Testcontainers Kafka), Redis, WireMock, Awaitility, Actuator + Prometheus, k6, GitHub Actions |

## What works today (Phases 1-2)

- `POST /api/orders` creates an order (returns `201 Created` with a `Location` header)
- `GET /api/orders/{id}` fetches an order (`200` or `404`)
- Request validation with RFC 7807 `ProblemDetail` error bodies (`400`, `404`, `409`)
- Orders and items persisted in PostgreSQL, with the schema owned by Flyway migrations
- Database-level constraints (`CHECK`, foreign key, `NOT NULL`) as a second line of defense behind application validation
- Optimistic locking (`@Version`) with concurrent-modification conflicts mapped to `409`
- A paginated repository query, `findByCustomerId`, backed by a composite index (repository-level only; not exposed over HTTP yet)
- An order lifecycle state machine enforced in the domain model
- 27 automated tests, including repository tests against a real PostgreSQL container

### Order lifecycle

```
CREATED -> PAYMENT_PENDING -> PAID -> INVENTORY_PENDING -> CONFIRMED

CREATED / PAYMENT_PENDING / INVENTORY_PENDING -> CANCELLED
CONFIRMED and CANCELLED are terminal.
```

`PAID -> CANCELLED` is intentionally not allowed. Cancelling a paid order requires a refund flow, which is a later design decision.

## Database and migrations

The schema is owned by [Flyway](https://flywaydb.org/) migrations in `src/main/resources/db/migration/`. Hibernate runs with `ddl-auto: validate`, so the application refuses to start if an entity and the schema disagree.

| Migration | Purpose |
|---|---|
| `V1__create_orders.sql` | `orders` and `order_items` tables, constraints, initial indexes |
| `V2__orders_customer_created_index.sql` | Composite index `(customer_id, created_at DESC)` replacing the single-column customer index |

**Never edit an applied migration.** Flyway checksums every applied file, so changes go into a new `V<n>` file.

### Constraints enforced by the database

| Constraint | Rule |
|---|---|
| `ck_orders_status` | status must be one of the six `OrderStatus` values |
| `ck_orders_total_nonnegative` | `total_amount >= 0` |
| `ck_order_items_quantity` | `quantity > 0` |
| `ck_order_items_unit_price` | `unit_price > 0` |
| `fk_order_items_order` | every item references an existing order |
| `NOT NULL` | on every column the entity marks as required, including `version` |

Indexes: `orders(status)`, `orders(customer_id, created_at DESC)`, `order_items(order_id)`. Postgres does not index foreign keys automatically, so the `order_items(order_id)` index is deliberate.

## Run locally

**Prerequisites:** JDK 21, Docker Desktop. Maven is not required (use the wrapper).

```bash
# 1. Start PostgreSQL (host port 5433 -> container 5432)
docker compose up -d

# 2. Build and run all tests (Docker must be running; see note below)
./mvnw clean verify          # Windows PowerShell: .\mvnw.cmd clean verify

# 3. Run the app on http://localhost:8080 (Flyway migrates the database on startup)
./mvnw spring-boot:run       # Windows PowerShell: .\mvnw.cmd spring-boot:run
```

> **Tests and Docker:** the tests start their own throwaway PostgreSQL container through Testcontainers, so they need Docker running but do **not** need the Compose container. The Compose container is only for running the app itself.

> **Resetting the local database:** `docker compose down -v` deletes the data volume. Flyway will rebuild the schema from the migrations on the next start. This is also required if the database was ever created by an older version of the project that used `ddl-auto: update`, because Flyway refuses to adopt a non-empty schema it did not create.

> **Local-dev credentials only.** The database name, user, and password are all `orders` in `docker-compose.yaml` and `application.yml`. They are throwaway defaults for a local container with no real data. Never commit real credentials.

> **Port note:** PostgreSQL is exposed on host port **5433**, not 5432, to avoid clashing with a locally installed PostgreSQL. If you change it, update both `docker-compose.yaml` and the JDBC URL in `application.yml`.

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
| Order not found | `404 Not Found` |
| Illegal order state transition | `409 Conflict` |
| Concurrent modification (stale optimistic-lock version) | `409 Conflict` |

## Project structure

```
src/main/java/com/krishna/order_platform/order/
├── api/        CreateOrderRequest, OrderResponse, OrderController, GlobalExceptionHandler
├── service/    OrderService, OrderNotFoundException
├── repo/       OrderRepository
└── domain/     Order, OrderItem, OrderStatus, InvalidStateTransitionException

src/main/resources/
├── application.yml
└── db/migration/   V1__create_orders.sql, V2__orders_customer_created_index.sql

src/test/java/com/krishna/order_platform/
├── TestcontainersConfiguration.java      shared PostgreSQL container (@ServiceConnection)
├── OrderPlatformApplicationTests.java
└── order/{api, service, domain, repo}/   test classes mirror the main packages
```

Packaged by feature rather than by layer, so future Inventory and Payment features get their own packages.

## Testing

27 tests, all passing.

| Test class | Type | Tests | Covers |
|---|---|---|---|
| `OrderStatusTest` | Unit | 4 | Valid transitions, no skipped steps, terminal states |
| `OrderTest` | Unit | 4 | Initial status, total calculation, transitions, empty-order rejection |
| `OrderServiceTest` | Unit (Mockito) | 2 | Total calculation, repository save, not-found handling |
| `OrderControllerTest` | Web layer (`@WebMvcTest`) | 6 | 201 + `Location`, validation failures (400), 404, optimistic-lock conflict (409) |
| `OrderRepositoryTest` | Persistence (`@DataJpaTest` + Testcontainers) | 10 | See below |
| `OrderPlatformApplicationTests` | Smoke | 1 | Full Spring context loads against a Testcontainers PostgreSQL |

`OrderRepositoryTest` covers:

- Flyway migrates an empty database and the entities validate against it
- Save and reload an order with items (first-level cache cleared so the database is really read)
- The database rejects zero quantity, zero unit price, an unknown status (via raw SQL), and an orphan item with no parent order, asserting the **constraint name** that fired
- `@Version` increments when an order changes
- A stale update fails with `OptimisticLockingFailureException`, and the first writer's change survives
- A rolled-back transaction leaves nothing persisted
- `findByCustomerId` returns only one customer's orders, newest first, with correct paging

## Design decisions

1. **Constructor injection only.** Dependencies are explicit and final, which keeps testing simple.
2. **`BigDecimal` for money.** `double` has binary rounding errors. Amounts are stored as `NUMERIC(12,2)`, and tests compare with `compareTo`, not `equals`.
3. **DTOs are separate from entities.** The API contract is decoupled from persistence, and lazy-loaded data never leaks into serialization.
4. **State machine lives in the enum.** Transition rules sit in one place (`OrderStatus.canTransitionTo`), are trivial to unit test, and cannot be bypassed because `Order` has no `setStatus`; all changes go through `transitionTo`.
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

## Known limitations

- `POST /api/orders` is not idempotent; a client retry creates a duplicate order.
- Nothing publishes events yet. When Kafka is added, the plan is to avoid a dual-write (database + Kafka) with a transactional outbox, never by calling Kafka inside the order-creation transaction.
- `findByCustomerId` is not exposed over HTTP; there is no list endpoint yet.
- Validation errors return a generic `detail` ("Invalid request content.") without per-field messages.
- `OrderService` still depends on API types (`CreateOrderRequest`, `OrderResponse`); a command object in and a service-level view out would be cleaner.

## Roadmap

| Phase | Scope | Status |
|---|---|---|
| 1 | Java 21, Spring Boot, Maven, REST API, PostgreSQL, basic tests | Done |
| 2 | Flyway migrations, constraints, transactions, repository tests, Testcontainers PostgreSQL | Done |
| 3 | Kafka producer/consumer, `OrderCreated`, transactional outbox, consumer group, partition key, Testcontainers Kafka | Next |
| 4 | Inventory service, reservation, concurrency protection, `InventoryReserved/Rejected`, concurrency test | Planned |
| 5 | Payment workflow, WireMock, retries, timeouts, failure events | Planned |
| 6 | Event IDs and idempotent consumers, duplicate-event integration test | Planned |
| 7 | Retry policy and dead-letter topic | Planned |
| 8 | Actuator + Prometheus metrics | Planned |
| 9 | k6 load testing with real, measured results | Planned |
| 10 | Docker/CI cleanup, then optional Terraform/AWS | Planned |

No performance numbers are claimed until they are actually measured in Phase 9.