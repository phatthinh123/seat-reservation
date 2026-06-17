# Agent: Milestone 5 — Tests & Documentation

## Your Role
You are the testing and documentation agent. All features are implemented.
Your job is to write the test suite that PROVES the concurrency claims, add unit tests
for the business logic, and write the README so a reviewer can run the project in minutes.

## Reference Files
- `.gemini/skills/concurrency-patterns.md` — full ConcurrentBookingTest pattern
- `IMPLEMENTATION_PLAN.md` — Section 4.12, Section 10 (trade-offs)

## Tests to Write

### 1. ConcurrentBookingTest.java
Path: `backend/src/test/java/com/linkz/seatreservation/concurrency/ConcurrentBookingTest.java`

Use the pattern from `.gemini/skills/concurrency-patterns.md` exactly.
Add Testcontainers annotations:
```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class ConcurrentBookingTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("seatreservation_test")
        .withUsername("seat").withPassword("seat");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }
    
    // 4 test methods:
    // 1. only_one_of_100_concurrent_requests_should_succeed
    // 2. same_idempotency_key_returns_same_booking
    // 3. webhook_processed_exactly_once_when_delivered_twice
    // 4. expired_hold_releases_seat_back_to_available
}
```

### 2. BookingServiceTest.java
Path: `backend/src/test/java/com/linkz/seatreservation/business/BookingServiceTest.java`

Pure unit test — NO Spring context, use Mockito:
```java
class BookingServiceTest {
    SeatRepositoryPort seatRepo = mock(SeatRepositoryPort.class);
    BookingRepositoryPort bookingRepo = mock(BookingRepositoryPort.class);
    DistributedLockPort lock = mock(DistributedLockPort.class);
    AuditPort audit = mock(AuditPort.class);
    
    BookingService service = new BookingService(seatRepo, bookingRepo, lock, audit);
    
    @Test void holdSeat_whenAvailable_shouldCreatePendingBooking() { ... }
    @Test void holdSeat_whenHeld_shouldThrowSeatUnavailableException() { ... }
    @Test void holdSeat_withSameIdempotencyKey_shouldReturnExistingBooking() { ... }
    @Test void holdSeat_whenLockFails_shouldThrowSeatUnavailableException() { ... }
}
```

### 3. WebhookServiceTest.java
Path: `backend/src/test/java/com/linkz/seatreservation/business/WebhookServiceTest.java`

Test the state machine:
```java
class WebhookServiceTest {
    // Mock all ports
    
    @Test void handleWebhook_whenBookingPending_shouldConfirmBooking() { ... }
    @Test void handleWebhook_whenBookingExpired_shouldTriggerRefund() { ... }
    @Test void handleWebhook_whenBookingAlreadyConfirmed_shouldLogDuplicate() { ... }
    @Test void handleWebhook_withDuplicateEventId_shouldSkipProcessing() { ... }
}
```

### 4. WebhookControllerTest.java
Path: `backend/src/test/java/com/linkz/seatreservation/web/WebhookControllerTest.java`

Test HMAC verification:
```java
@WebMvcTest(WebhookController.class)
class WebhookControllerTest {
    @Test void webhook_withValidSignature_shouldReturn200() { ... }
    @Test void webhook_withInvalidSignature_shouldReturn400() { ... }
    @Test void webhook_withMissingSignature_shouldReturn400() { ... }
}
```

### 5. application-test.yml
```yaml
spring:
  jpa.hibernate.ddl-auto: create-drop
  flyway.enabled: true
webhook.secret: test-secret
mock-payment.url: http://localhost:9999
```

## README.md (project root)

Write a complete README with these sections:

### Architecture
Brief description + architecture diagram (ASCII):
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

### Quick Start
```bash
git clone <repo>
cd seat-reservation

# Copy and configure env
cp .env.example .env

# Start everything
docker compose up --build -d

# Wait ~60s for Keycloak to start
# Open http://localhost:4200
# Login: user@linkz.com / User1234!
# Admin: admin@linkz.com / Admin1234!
```

### Tech Stack table

### Key Design Decisions
For each trade-off in IMPLEMENTATION_PLAN.md Section 10, write 2-3 sentences.
These are the most important sections for the reviewer.

### Concurrency Strategy
Explain 3-layer defense.

### Webhook Reliability
Explain persist-first, idempotency, state machine, reconciliation.

### Running Tests
```bash
./gradlew test
# ConcurrentBookingTest uses Testcontainers (Docker required)
```

### Environment Variables Table (from .env.example)

### Credentials (for local dev only)

## .env.example

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

## Verification Steps

```bash
# Run all tests
cd backend && ../gradlew test

# Check test report
open build/reports/tests/test/index.html

# Verify concurrency test passes
# Look for ConcurrentBookingTest in report — all 4 tests should be green
```

## Definition of Done

✅ `./gradlew test` passes (all tests green)
✅ ConcurrentBookingTest: exactly 1 success, 99 conflicts verified
✅ BookingServiceTest: 4 unit tests pass without Spring context
✅ WebhookServiceTest: state machine tested for all 4 booking states
✅ WebhookControllerTest: HMAC valid/invalid/missing tested
✅ README.md: reviewer can run project with just docker compose up
✅ All trade-offs from IMPLEMENTATION_PLAN.md are documented in README
✅ .env.example has every variable with description
