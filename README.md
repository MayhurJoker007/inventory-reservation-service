# Inventory reservation service

![CI](https://github.com/MayhurJoker007/inventory-reservation-service/actions/workflows/ci.yml/badge.svg)

Holds stock for an order while the buyer checks out, then either commits it or gives it back. It's the part of an
e-commerce backend that has to be right under load, because the failure mode is selling stock you don't have.

**Stack:** Java 21, Spring Boot 4, Spring Data JPA, Flyway, PostgreSQL (H2 in Postgres mode for tests), Docker,
GitHub Actions.

```
POST /api/reservations          HELD       stock: reserved += qty
POST /api/reservations/{id}/confirm   CONFIRMED  stock: on_hand -= qty, reserved -= qty
POST /api/reservations/{id}/release   RELEASED   stock: reserved -= qty
(after the hold time, automatically)  EXPIRED    stock: reserved -= qty
```

Available stock is always `on_hand - reserved`.

## What makes it safe

**No overselling under concurrency.** Each stock row has a `@Version` column. When two checkouts race for the last
cases, the second commit fails its optimistic lock check and is **retried in a fresh transaction** with fresh
numbers (up to 5 times, with a small random backoff so retries don't collide again). The test fires 24
simultaneous buyers at 10 units and checks that exactly 10 succeed and the other 14 are refused.

**And the database agrees.** `CHECK (reserved >= 0 AND reserved <= on_hand)` means that even a bug in the
application can't oversell. The constraint will reject it.

**All or nothing.** If one line of an order can't be filled, nothing is held, and the 409 response lists every
line that's short:

```json
{ "status": 409, "detail": "Not enough stock",
  "problems": ["HB-WATER-500: requested 999, only 900 available"] }
```

**Idempotent.** Every reserve needs an `Idempotency-Key` header. A checkout that times out and retries gets
the same reservation back instead of holding the stock twice. A unique constraint on the key handles two copies
arriving at the same moment. Confirming twice (a payment callback that fires twice) is also harmless.

**Abandoned checkouts give stock back.** A scheduled job expires holds after 15 minutes (configurable per
request, up to 4 hours). A hold that has passed its expiry can't be confirmed, even if the job hasn't run yet.

**Sensible warehouse choice.** Without a warehouse, each line goes to the warehouse with the most available
that can fill it *on its own*. Splitting one line across two warehouses means two deliveries.

## Running

```
docker compose up
```

That starts Postgres and the service on http://localhost:8080. Flyway creates the schema and some demo stock.
Or without Docker, using in-memory H2:

```
./mvnw spring-boot:run
```

```
curl -X POST localhost:8080/api/reservations -H "Content-Type: application/json" -H "Idempotency-Key: abc-123" \
     -d '{"orderRef": "MP-00001234", "lines": [{"sku": "HB-COLA-330", "quantity": 150}]}'

curl localhost:8080/api/stock/HB-COLA-330
```

| | | |
|---|---|---|
| POST | `/api/reservations` | hold stock (`Idempotency-Key` header required) |
| GET | `/api/reservations/{id}` | |
| POST | `/api/reservations/{id}/confirm` | commit it |
| POST | `/api/reservations/{id}/release` | give it back |
| GET | `/api/stock/{sku}` | on hand / reserved / available per warehouse |
| POST | `/api/stock/{sku}/receive` | goods received at a warehouse |

## Tests

```
./mvnw test
```

12 tests cover reserve, confirm and release, all-or-nothing, idempotency, warehouse choice, expiry with a
controllable clock, confirming after expiry, double confirms, the concurrency test above, and the HTTP layer.

CI runs them, then starts the built jar against a real **PostgreSQL 16** service container and pushes a
reservation through, so the Flyway migrations are proven on Postgres and not only on H2.
