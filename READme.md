# Event-Driven Order & Inventory Platform

A backend system built with **Java 21, Spring Boot, PostgreSQL, and (in later phases) Kafka**. The goal is to model a realistic order-processing backend: an order API, a strict order lifecycle, and, as the project grows, asynchronous payment and inventory workflows that stay correct under duplicate events, transient failures, and concurrency.

> **Status:** Phase 1 complete (order API + persistence + tests). Kafka, inventory, payment, idempotency, and observability are planned. See [Roadmap](#roadmap).

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
| Database | PostgreSQL 16 (Docker Compose) |
| Build | Maven (wrapper included) |
| Testing | JUnit 5, Mockito, Spring MockMvc |
| Planned | Flyway, Testcontainers, Kafka, Redis, WireMock, Awaitility, Actuator + Prometheus, k6, GitHub Actions |

## What works today (Phase 1)

- `POST /api/orders` creates an order (returns `201 Created` with a `Location` header)
- `GET /api/orders/{id}` fetches an order (`200` or `404`)
- Request validation with RFC 7807 `ProblemDetail` error bodies (`400`, `404`, `409`)
- Orders and items persisted in PostgreSQL
- An order lifecycle state machine enforced in the domain model
- Unit and web-layer tests

### Order lifecycle

```
CREATED -> PAYMENT_PENDING -> PAID -> INVENTORY_PENDING -> CONFIRMED

CREATED / PAYMENT_PENDING / INVENTORY_PENDING -> CANCELLED
CONFIRMED and CANCELLED are terminal.
```

`PAID -> CANCELLED` is intentionally not allowed. Cancelling a paid order requires a refund flow, which is a later design decision.

## Run locally

**Prerequisites:** JDK 21, Docker Desktop. Maven is not required (use the wrapper).

```bash
# 1. Start PostgreSQL (host port 5433 -> container 5432)
docker compose up -d

# 2. Build and run all tests
./mvnw clean verify          # Windows PowerShell: .\mvnw.cmd clean verify

# 3. Run the app on http://localhost:8080
./mvnw spring-boot:run       # Windows PowerShell: .\mvnw.cmd spring-boot:run
```

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

## Project structure

```
src/main/java/com/krishna/order_platform/order/
├── api/        CreateOrderRequest, OrderResponse, OrderController, GlobalExceptionHandler
├── service/    OrderService, OrderNotFoundException
├── repo/       OrderRepository
└── domain/     Order, OrderItem, OrderStatus, InvalidStateTransitionException
```

Packaged by feature rather than by layer, so future Inventory and Payment features get their own packages.

## Testing

| Test class | Type | Covers |
|---|---|---|
| `OrderStatusTest` | Unit | Valid transitions, no skipped steps, terminal states |
| `OrderTest` | Unit | Initial status, total calculation, transitions, empty-order rejection |
| `OrderServiceTest` | Unit (Mockito) | Total calculation, repository save, not-found handling |
| `OrderControllerTest` | Web layer (`@WebMvcTest`) | 201 + `Location`, validation failures (400), 404 |
| `OrderPlatformApplicationTests` | Smoke | Full Spring context loads against PostgreSQL |

Run everything with `./mvnw clean verify`. The full-context test needs the PostgreSQL container running; repository tests against a real database via Testcontainers are planned for Phase 2.

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

## Known limitations (Phase 1)

- Schema is generated with `spring.jpa.hibernate.ddl-auto: update`. This is a placeholder; Phase 2 replaces it with versioned migrations and `validate`.
- No repository or integration tests against a real PostgreSQL yet (Testcontainers is planned).
- Optimistic-locking conflicts are not yet mapped to a `409` response.
- `POST /api/orders` is not idempotent; a client retry creates a duplicate order.
- Nothing publishes events yet. When Kafka is added, the plan is to avoid a dual-write (database + Kafka) by using a transactional outbox or a publish-after-commit approach.

## Roadmap

| Phase | Scope | Status |
|---|---|---|
| 1 | Java 21, Spring Boot, Maven, REST API, PostgreSQL, basic tests | Done |
| 2 | Migrations, constraints, transactions, repository tests, Testcontainers PostgreSQL | Next |
| 3 | Kafka producer/consumer, `OrderCreated`, consumer group, partition key, Testcontainers Kafka | Planned |
| 4 | Inventory service, reservation, concurrency protection, `InventoryReserved/Rejected`, concurrency test | Planned |
| 5 | Payment workflow, WireMock, retries, timeouts, failure events | Planned |
| 6 | Event IDs and idempotent consumers, duplicate-event integration test | Planned |
| 7 | Retry policy and dead-letter topic | Planned |
| 8 | Actuator + Prometheus metrics | Planned |
| 9 | k6 load testing with real, measured results | Planned |
| 10 | Docker/CI cleanup, then optional Terraform/AWS | Planned |

No performance numbers are claimed until they are actually measured in Phase 9.