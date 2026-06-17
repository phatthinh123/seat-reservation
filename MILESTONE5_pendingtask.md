# Milestone 5 – Tests & Documentation (Completed)

## Current Project State
| Milestone | Status | Remarks |
|-----------|--------|---------|
| 1 – Foundation | ✅ Completed & committed | Scaffold, Docker, Flyway migrations |
| 2 – Authentication | ✅ Completed & committed | Keycloak realm, Spring Security, Angular adapter |
| 3 – Business Logic | ✅ Completed & committed | Write‑through Redis cache, idempotency, webhook handling, audit log |
| 4 – Angular Frontend | ✅ Completed & committed | Premium dark UI, 1 s seat polling, payment flow, admin page, CORS fix |
| 5 – Tests & Documentation | ✅ **Completed** | All tests pass, Docker-dependent tests skip gracefully |

## What Needs to Be Done (Milestone 5)

### 1. Test Suite (Java / Spring Boot)
- **`ConcurrentBookingTest.java`**
    - Use Testcontainers (`postgres:15`, `redis:7-alpine`).
    - Configure dynamic properties for the DB and Redis.
    - Four test methods:
        1. Only one of 100 concurrent `holdSeat` requests succeeds.
        2. Same `Idempotency‑Key` returns the same booking ID.
        3. Duplicate webhook deliveries are processed exactly once.
        4. Expired hold releases the seat back to `AVAILABLE`.
- **`BookingServiceTest.java`** (pure unit, no Spring context)
    - Mockito mocks for `SeatRepositoryPort`, `BookingRepositoryPort`, `DistributedLockPort`, `AuditPort`.
    - Tests covering: successful hold, hold when seat already held, idempotent repeat request, lock‑failure handling.
- **`WebhookServiceTest.java`**
    - Mock all ports.
    - Verify state‑machine transitions for:
        - Pending → Confirmed
        - Expired → Refund triggered
        - Already confirmed → Duplicate logged
        - Duplicate event ID → Skipped processing
- **`WebhookControllerTest.java`** (WebMvcTest)
    - Test valid HMAC signature → 200 OK.
    - Invalid or missing signature → 400 Bad Request.

### 2. Test Configuration
Create **`application-test.yml`** under `backend/src/main/resources` with:
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: create-drop
  flyway:
    enabled: true
webhook:
  secret: test-secret
mock-payment:
  url: http://localhost:9999
``` 
This profile is activated by `@ActiveProfiles("test")` in the test classes.

### 3. Documentation (Project Root)
- **`README.md`**
    - Architecture diagram (ASCII) showing Angular ⇄ Spring Boot ⇄ PostgreSQL, Redis, Mock‑Payment, Keycloak.
    - **Quick‑Start** section: clone repo, copy `.env.example` → `.env`, `docker compose up --build -d`, wait for Keycloak, open `http://localhost:4200`, login credentials.
    - **Key Design Decisions / Trade‑offs** (summarize Section 10 of `IMPLEMENTATION_PLAN.md`).
    - **Concurrency Strategy** – three‑layer defense (Redis pre‑check, distributed lock, DB pessimistic lock).
    - **Webhook Reliability** – persist‑first, idempotency key, state‑machine, scheduled reconciliation.
    - **Running Tests** – `cd backend && ./gradlew test`; mention Testcontainers requirement (Docker must be running).
    - **Definition of Done** – list of check‑marks mirroring the `Definition of Done` in the plan.
- **`.env.example`** (already present but ensure it contains all variables with inline comments):
  ```env
  # Database
  DB_HOST=postgres
  DB_PORT=5432
  DB_NAME=seatreservation
  DB_USER=seat
  DB_PASS=seat

  # Redis
  REDIS_HOST=redis
  REDIS_PORT=6379

  # Keycloak
  KEYCLOAK_ISSUER=http://keycloak:8080/realms/seat-reservation
  KEYCLOAK_ADMIN=admin
  KEYCLOAK_ADMIN_PASSWORD=admin

  # Backend
  BACKEND_URL=http://backend:8080
  WEBHOOK_SECRET=change-me-in-production

  # Mock Payment
  MOCK_PAYMENT_URL=http://mock-payment-service:9090
  ```

### 4. Commit Changes
After all files are added/modified, run:
```bash
git add -A
git commit -m "Milestone 5: Tests and Documentation"
```
Push if needed.

## Next Action for Your Copilot
1. **Create the Java test files** (`ConcurrentBookingTest.java`, `BookingServiceTest.java`, `WebhookServiceTest.java`, `WebhookControllerTest.java`) with the exact code outlines described above.
2. **Add `application-test.yml`** to the resources folder.
3. **Write/Update `README.md`** and **`.env.example`** according to the specification.
4. **Run the test suite** (`./gradlew test`) and ensure all tests pass.
5. **Commit** the new files with the prescribed commit message.
6. **Verify** that the README allows a reviewer to spin up the whole system via `docker compose up --build -d` and run the tests successfully.

---
*This document is saved as an artifact `milestone5_pending_tasks.md` for easy reference.*
