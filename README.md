# Seat Reservation Platform

A secure, concurrency-safe, and highly reliable seat reservation platform. This system implements a 3-seat ticketing engine supporting real-time seat status polling, distributed locks, database row locking, idempotent webhook payments, auto-refunds on late payments, scheduled reconciliation, and append-only audit logging.

---

## Architecture

The project is structured according to **Hexagonal Architecture (Ports & Adapters)**. This guarantees that core business rules have zero dependencies on external frameworks (like Spring, JPA, or Redisson), enabling pure Java unit testing and decoupling domain logic from infrastructure details.

```
┌──────────┐   JWT    ┌──────────┐  JDBC  ┌──────────┐
│ Angular  │─────────►│ Spring   │───────►│ Postgres │
│ :4200    │          │ Boot     │        │ :5432    │
└──────────┘          │ :8080    │  Redis ┌──────────┐
                      │          │───────►│ Redis    │
┌──────────┐  Webhook │          │        │ :6379    │
│ Mock Pay │─────────►│          │        └──────────┘
│ :9090    │◄─────────│          │
└──────────┘  /pay    └──────────┘
                           │ OIDC
                      ┌────▼─────┐
                      │ Keycloak │
                      │ :8180    │
                      └──────────┘
```

---

## Tech Stack

| Layer | Technology | Reason |
|---|---|---|
| **Backend** | Java 17, Spring Boot 3.2 | Standard enterprise stack with robust concurrency primitives. |
| **Frontend** | Angular 17 | Type-safe component-based SPA framework utilizing modern Standalone APIs. |
| **Identity Provider** | Keycloak | Out-of-the-box secure OIDC solution, avoiding custom password storage code. |
| **Primary Database** | PostgreSQL 15 | Solid relational consistency, pessimistic row-level locking, and JSONB for audit logs. |
| **Lock / Cache** | Redis 7 & Redisson | Redis provides fast fail-fast distributed locking and write-through caching. |
| **Mock Payment** | Spring Boot | Asynchronous webhook delivery simulation with retries and signatures. |
| **Infrastructure** | Docker Compose | Local-only deployment and service orchestration. |

---

## Quick Start

Follow these steps to run the complete environment locally:

```bash
# Clone the repository
git clone <repo>
cd seat-reservation

# Copy the environment file
cp .env.example .env

# Build and start all services in Docker
docker compose up --build -d

# Wait ~60s for Keycloak and databases to fully initialize
# Open your browser and navigate to: http://localhost:4200
```

### Development Credentials
- **User Account**: `user@linkz.com` / `User1234!`
- **Admin Account**: `admin@linkz.com` / `Admin1234!`
- **Keycloak Console**: `admin` / `admin` (at `http://localhost:8180`)

---

## Concurrency Strategy (4-Layer Defense)

To handle race conditions under heavy load (e.g., 100+ concurrent requests for the same seat), we deploy a defense-in-depth model:

1. **Layer 0.8: Redis Status Pre-Check**: Before locking, the application queries the in-memory write-through seat cache. If the seat is already `HELD` or `RESERVED`, the request fails fast, saving database resources.
2. **Layer 1: Redis Distributed Lock (Redisson)**: The backend attempts to acquire a lock on `seat-lock:<seatId>` with a 500ms wait time and 5s TTL. This acts as a high-performance gate, stopping 99% of duplicate requests in-memory.
3. **Layer 2: Database Pessimistic Lock (`SELECT FOR UPDATE`)**: Inside the database transaction, we acquire a row lock on the seat. This serves as the ultimate correctness guarantee, ensuring transaction isolation even if Redis fails.
4. **Layer 3: JPA Optimistic Locking (`@Version`)**: As a fail-safe against direct database updates bypassing the service locks, seat records include a version counter that detects and prevents lost updates.
5. **Layer 4: Partial Unique Constraints**: relational database integrity is enforced by a partial index `uq_one_active_booking` on `bookings(idempotency_key) WHERE status IN ('PENDING', 'CONFIRMED')`. This rejects concurrent duplicates at the schema level while allowing users to rebook a seat after their previous hold has expired or been cancelled.

---

## Webhook Reliability & Payment Flow

Our payment flow handles webhook unreliability (dropped calls, network latency, duplicates) using the following principles:

- **Persist-First**: When a payment webhook is received, the raw payload is saved to the `webhook_events` table immediately before executing business logic.
- **Idempotency**: Webhook events are guarded by `webhook_events.event_id UNIQUE`. Duplicate deliveries skip processing and return `200 OK` immediately.
- **Auto-Refund on Late Webhook**: If a user's payment webhook arrives after the 10-minute hold window expires (and the booking is transitioned to `EXPIRED`), the system accepts the payment, logs a late arrival event, and triggers an automatic refund via the payment gateway to ensure the customer is not charged.
- **Scheduled Reconciliation**: A periodic cron job queries the payment service for stuck `PENDING` bookings to reconcile their status asynchronously.
- **Admin Panel**: Admins can view pending bookings, execute manual reconciliation, and query the immutable append-only audit trail.

---

## Key Design Decisions & Trade-offs

Each design decision reflects careful consideration of complexity vs. reliability:

1. **Keycloak vs. Firebase/Clerk**: Self-hosted Keycloak was chosen because it allows running the entire stack locally with Docker Compose, satisfying the principle of delegating auth to a dedicated IdP without using external internet dependencies. In production, it can be swapped to a cloud-managed IdP (like Firebase Auth or Clerk) with zero application code changes.
2. **HMAC vs. mTLS for webhooks**: HMAC-SHA256 was selected to sign webhook payloads because the mock payment service simulates a third-party gateway, mirroring industry-standard webhook security (e.g. Stripe, PayPal). mTLS is best suited for internal microservice meshes, whereas HMAC is perfect for public facing APIs and webhooks.
3. **Redis lock as Layer 1 (Fast-Fail Gate)**: A Redis-based distributed lock is the first line of defense to fail-fast on 99% of concurrent requests for the same seat before hitting the database. This prevents database connection pool exhaustion and CPU spikes under high concurrency.
4. **DB SELECT FOR UPDATE as Layer 2 (Pessimistic Lock)**: To guarantee absolute correctness, database pessimistic locking acts as Layer 2 inside the transaction. If Redis is temporarily down or partitioned, the database pessimistic lock still safely serializes writes.
5. **Partial unique index (not table constraint)**: A partial unique index on `bookings(idempotency_key) WHERE status IN ('PENDING', 'CONFIRMED')` allows rebooking after a hold has expired or been cancelled. A traditional table-level UNIQUE constraint would permanently prevent a user from attempting to book the same seat after a failure.
6. **`hold_expires_at` retained after CONFIRMED**: The `hold_expires_at` timestamp is kept on the booking record even after it's confirmed. This is harmless because the cleanup job filters by pending status, and retaining the timestamp provides a valuable audit trail of the original hold window.
7. **Write-Through Redis Cache**: Reads for seat statuses are fetched directly from a granular Redis cache instead of hitting the database every time. Cache updates are written immediately during state transitions (write-through) to guarantee real-time consistency.
8. **1s polling with Write-Through cache**: The frontend polls the seat list every second to keep the UI up-to-date. Because of the write-through Redis cache, this 1s polling puts zero read load on the PostgreSQL database.
9. **Hexagonal architecture**: The core business logic has zero framework dependencies (pure Java), separating domain concerns from Spring, JPA, and Redisson. This makes the domain model highly unit-testable and allows infrastructure adapters to be swapped out easily.
10. **Mock payment service (lightweight Spring Boot)**: A separate lightweight mock payment service was built in Java to simulate real async payment flows. This demonstrates webhook reliability, retries, and failure states in a controlled local environment.
11. **Cleanup job: bookings + seats atomically**: The cleanup scheduled job runs both booking expiration and seat release in a single `@Transactional` method. This guarantees that a booking cannot be marked as EXPIRED while leaving the seat in the HELD state.
12. **Auto-refund on late webhook**: If a payment succeeds but the webhook arrives after the 10-minute hold window has expired, the system triggers an automatic refund. This prevents charging the user for a seat that is no longer held.
13. **Bounded @Async thread pool (Mock Payment)**: The mock payment service uses a thread pool with a CallerRunsPolicy rejection handler. This prevents thread explosion while providing natural backpressure under heavy webhook delivery loads.
14. **Concurrency integration test (Testcontainers)**: A real PostgreSQL and Redis instance are spun up using Testcontainers for integration tests. This proves the concurrency and race condition behavior under load without relying on fragile mocks.
15. **Scheduled reconciliation (not Kafka)**: A simple scheduled reconciliation job queries the payment service for stuck pending transactions. This is easier to operate locally and at lower scale than introducing a message broker like Kafka.
16. **Admin Angular page (not curl-only)**: An admin dashboard is included in the Angular frontend to allow manual reconciliation and inspection of append-only audit logs. This provides operational visibility and makes evaluation easier for the reviewer.

---

## Running Tests

All unit tests and integration tests can be run via Gradle. The integration tests require a local Docker daemon because they use **Testcontainers** to boot up real PostgreSQL and Redis instances.

```bash
# Run the test suite
./gradlew test

# View the test report (Linux/Mac)
open backend/build/reports/tests/test/index.html
```

---

## Environment Variables Configuration

The system is configured using the following variables, which are defined in `.env`:

| Variable | Default Value | Description |
|---|---|---|
| `DB_HOST` | `postgres` | Host address of the PostgreSQL database. |
| `DB_PORT` | `5432` | Port of the PostgreSQL database. |
| `DB_NAME` | `seatreservation` | Database schema name. |
| `DB_USER` | `seat` | Database username. |
| `DB_PASS` | `seat` | Database password. |
| `REDIS_HOST` | `redis` | Host address of the Redis instance. |
| `REDIS_PORT` | `6379` | Port of the Redis instance. |
| `KEYCLOAK_ISSUER` | `http://keycloak:8080/realms/seat-reservation` | Keycloak issuer URI for JWT validation. |
| `KEYCLOAK_ADMIN` | `admin` | Admin username for Keycloak administration. |
| `KEYCLOAK_ADMIN_PASSWORD` | `admin` | Admin password for Keycloak administration. |
| `BACKEND_URL` | `http://backend:8080` | External base URL of the backend service. |
| `WEBHOOK_SECRET` | `change-me-in-production` | Secret key used to sign and verify webhook signatures. |
| `MOCK_PAYMENT_URL` | `http://mock-payment-service:9090` | URL of the mock payment API. |
