# Implementation Plan
## Linkz — Lead Engineer Technical Assessment
### Stack: Java 17 · Spring Boot 3 · Angular 17 · Keycloak · PostgreSQL · Redis · Docker Compose

---

## 1. What We Are Building

A **seat reservation platform** with 3 seats. Authenticated users can:
1. Log in (90-day session)
2. View 3 seats (`AVAILABLE` / `HELD` / `RESERVED`)
3. Select a seat → seat goes into **HELD** state (10-min timeout)
4. Pay for the held seat
5. On payment success → seat becomes **RESERVED**
6. On payment failure or hold timeout → seat releases back to **AVAILABLE**

The assessment evaluates **engineering judgment**, not feature completeness.
We must demonstrate: security, concurrency safety, reliability, auditability, forward-thinking design.

---

## 2. Confirmed Tech Stack

| Layer | Technology | Reason |
|---|---|---|
| Backend | Java 17, Spring Boot 3.x | Standard enterprise stack, robust concurrency |
| Frontend | Angular 17 (Standalone API) | Modern, type-safe component-based SPA framework |
| Auth | **Keycloak** (Docker) | Managed IdP — no custom password code |
| Database | PostgreSQL 15 | Row-level locking, JSONB for audit |
| Cache / Lock | **Redis 7** (Docker) | Distributed lock (Redisson) + seat status cache |
| Build | Gradle (backend), npm (frontend) | Standard build tools for Java and web ecosystems |
| Infra | Docker Compose | Local-only deployment |
| Payment | **Mock Payment Service** (Spring Boot, lightweight) | Simulates full webhook flow locally |

> Redis serves dual purpose: distributed locking (Redisson) as first concurrency layer,
> and seat status cache to reduce DB reads on every poll.

---

## 3. Addressing Every Guideline Criterion

### 3.1 Authentication & Identity Management *(Mandatory)*

**Decision:** We delegate identity management to a dedicated Identity Provider (Keycloak) rather than implementing custom password storage.
**Why it helps:** This avoids the security risks, liabilities, and compliance overhead of storing and managing user credentials. Our Spring Boot backend remains lightweight, acting strictly as an OAuth2 Resource Server that validates signed JWTs without having to implement password hashing, salts, resets, or Multi-Factor Authentication (MFA). Sourcing an external provider also means adding Google or social OAuth in the future requires zero backend code changes.

> **Trade-off (README):** Guideline names Firebase Auth/Clerk. Keycloak satisfies the same principle
> (managed IdP, no custom password code) while being self-hostable for Docker Compose.
> In production: swap to a cloud-managed IdP — zero application code change.

---

### 3.2 Session Management & Future Mobile App Support

**Decision:** We use stateless, signed JSON Web Tokens (JWT) for session authentication rather than server-side `HttpSession` state.
**Why it helps:** By keeping the backend completely stateless, we can horizontally scale the application instances without needing session replication or sticky routing. Because JWTs are standard and self-contained, this authentication model naturally extends to future mobile apps or third-party API clients, which can hit the exact same backend endpoints by simply attaching the token in the `Authorization: Bearer` header.

---

### 3.3 Concurrent Booking / Race Condition Handling *(Critical)*

**Decision:** We implement a 4-layer concurrency defense (Redis distributed locks, database pessimistic locks, JPA optimistic versioning, and partial database unique constraints).
**Why it helps:** Under heavy load (e.g., 100+ users trying to reserve the same seat concurrently), each layer serves a specific purpose to protect database resources while maintaining absolute transactional consistency:
- *Redis Locks:* Block concurrent requests for the same seat in-memory before they hit the database, preventing connection pool exhaustion and CPU spikes.
- *Database Pessimistic Locks:* Lock the seat row (`SELECT FOR UPDATE`) within the transaction to serialize DB writes, providing a bulletproof correctness guarantee if Redis goes down.
- *JPA Optimistic Versioning:* Protects the database from "lost updates" if an unexpected code path bypasses row locking and updates seat status concurrently.
- *Partial Unique Constraints:* Ensures relational integrity by rejecting duplicate active bookings at the schema level.

**4-layer defence — now with Redis as first gate:**

**Layer 1 — Redis Distributed Lock (Redisson) — fast-fail gate:**
```java
RLock lock = redissonClient.getLock("seat-lock:" + seatId);
try {
    // Try to acquire lock: wait max 500ms, hold for 5s
    if (!lock.tryLock(500, 5000, TimeUnit.MILLISECONDS)) {
        throw new SeatUnavailableException("SEAT_UNAVAILABLE");
    }
    // Only one thread per seatId reaches the DB layer
    bookingService.holdSeatInDb(seatId, userId, idempotencyKey);
} finally {
    if (lock.isHeldByCurrentThread()) lock.unlock();
}
```
- 100 concurrent requests for seat A1 → 1 acquires Redis lock, 99 fail-fast at Redis (no DB hit)
- Eliminates DB contention before it starts
- Stateless across multiple backend instances (distributed)

**Layer 2 — Pessimistic DB Lock (correctness guarantee):**
```sql
-- Inside @Transactional:
SELECT * FROM seats WHERE id = ? FOR UPDATE;
-- check AVAILABLE → set HELD → insert booking
-- Comment: // NOT a check-before-insert — atomic SELECT FOR UPDATE
```
- Guards against Redis failure or network partition (Redis down → DB lock still correct)
- One of the two layers MUST succeed; defence-in-depth

**Layer 3 — Optimistic Lock (last resort):**
- `seats.version` column + JPA `@Version`
- Catches direct DB writes or edge cases bypassing both locks above

**Layer 4 — Seat Hold TTL + Idempotency:**
- `hold_expires_at = NOW() + 10 min`, cleanup job runs every 1 min
- `Idempotency-Key` header: same request → same booking, no double-hold

> **Why Redis lock + DB lock and not just one?**
> Redis lock = performance (fail fast, no DB load).
> DB lock = correctness (Redis can fail; DB transaction cannot partially apply).
> Together they are complementary, not redundant.

---

### 3.4 Payment Webhook Reliability & Fallback

**Decision:** We implement raw-payload webhook logging, HMAC-SHA256 signature verification, and exactly-once processing (idempotency checks) for all payment callbacks.
**Why it helps:** Webhooks from payment gateways can fail, retry, or arrive out of order. Persisting the raw payload first ensures we never lose the transaction record if processing fails mid-execution. Checking the `event_id` against a unique constraint protects against double-processing duplicate webhook calls. Finally, if a webhook arrives after the booking hold window has expired, the system automatically triggers a refund to prevent billing customers for seats they did not secure.

**Mock Payment Service:**
- Signs webhooks with **HMAC-SHA256** shared secret (mirrors Stripe/PayPal pattern)
- Delivers webhook asynchronously; retries 3× with exponential backoff on non-200

**Webhook handler — strict ordering:**
1. Verify HMAC signature → reject (400) if invalid
2. **Persist raw payload to `webhook_events`** BEFORE any logic
3. Check `webhook_events.event_id UNIQUE` → duplicate? return 200 and skip
4. Load booking `FOR UPDATE` → state machine check:
   - `PENDING` → confirm (normal path)
   - `EXPIRED`/`CANCELLED` → log `WEBHOOK_LATE_ARRIVAL` + auto-refund
   - `CONFIRMED` → log `WEBHOOK_DUPLICATE`, return 200
5. **Single transaction:** `bookings.status` + `seats.status` + `payment_transactions.status` + audit log
6. Return 200 OK

**Fallback 1 — Scheduled reconciliation:** `@Scheduled` every 5 min, query mock payment API for stale PENDING bookings

**Fallback 2 — Manual admin tool:** `POST /api/admin/reconcile/{bookingId}`

> **Why HMAC and not mTLS?** — See Section 4.10

---

### 3.5 Audit Log for Payment & Critical Actions *(Good Practice)*

Append-only `audit_logs` — **NEVER UPDATE or DELETE**. Written in same DB transaction as business op.

| Action | Trigger |
|---|---|
| `SEAT_HELD` | User selects a seat |
| `SEAT_RELEASED` | Hold expired or payment failed |
| `SEAT_RESERVED` | Payment confirmed |
| `BOOKING_CREATED` | Booking record created |
| `BOOKING_CONFIRMED` | Webhook payment success received |
| `BOOKING_EXPIRED` | Scheduler releases expired hold |
| `BOOKING_CANCELLED` | Admin cancels |
| `PAYMENT_INITIATED` | Payment request sent |
| `PAYMENT_SUCCESS` | Webhook confirmed success |
| `PAYMENT_FAILED` | Webhook or reconciliation confirmed failure |
| `REFUND_INITIATED` | Auto-triggered on late webhook or admin action |
| `REFUND_COMPLETED` | Refund confirmed |
| `WEBHOOK_RECEIVED` | Raw event stored |
| `WEBHOOK_PROCESSED` | Event successfully handled |
| `WEBHOOK_DUPLICATE` | Duplicate event detected and skipped |
| `WEBHOOK_LATE_ARRIVAL` | Webhook arrived after booking EXPIRED — auto-refund triggered |
| `RECONCILIATION_RUN` | Scheduler ran reconciliation |
| `MANUAL_RECONCILE` | Admin triggered manual reconciliation |

Each entry: `created_at`, `actor` (userId or `"system"`), `action`, `entity_type`, `entity_id`, `before_state` (JSONB), `after_state` (JSONB)

---

### 3.6 Overall Mindset

- Keycloak = configure not build ✓
- Redis + DB layered concurrency — not naive ✓
- Webhook treated as unreliable — persist first, process second ✓
- Immutable audit log — compliance-ready ✓
- Stateless JWT — mobile-ready ✓
- Hexagonal architecture — testable, maintainable ✓
- All trade-offs documented ✓

---

## 4. Design Decisions

### 4.1 — Booking → Payment API Relationship

**Decision:** `POST /api/bookings/{id}/payment`

```
POST /api/bookings              → PENDING booking, returns bookingId + holdExpiresAt
POST /api/bookings/{id}/payment → initiate payment for this booking
  └─ verifies: booking.user_id == JWT sub, status == PENDING, hold not expired
  └─ calls mock payment service
  └─ returns paymentId
```

---

### 4.2 — Seat Ownership (Who Owns HELD?)

`seats` is a **denormalized display cache**. Ownership verified through `bookings`:
```sql
SELECT b.* FROM bookings b
WHERE b.seat_id = :seatId AND b.user_id = :userId
  AND b.status = 'PENDING' AND b.hold_expires_at > NOW()
FOR UPDATE;
```
If no match → reject. `bookings` table is the authoritative source.

---

### 4.3 — Idempotency Key Strategy

| Source | Key | Idempotent? |
|---|---|---|
| Client header | Use as-is | ✅ Client controls retry |
| No header | `SHA-256(userId + seatId)` | ✅ Deterministic per user+seat |

**Rebooking — partial unique index:**
```sql
-- Only ACTIVE bookings must be unique. EXPIRED/CANCELLED rows excluded → rebooking allowed.
CREATE UNIQUE INDEX uq_one_active_booking
  ON bookings (idempotency_key)
  WHERE status IN ('PENDING', 'CONFIRMED');
```

---

### 4.4 — Mock Payment Service Stack

Lightweight Spring Boot (Spring Web only, no JPA, in-memory `HashMap<String, PaymentStatus>`).
Same language as backend — reviewer reads one codebase. Part of Gradle multi-project build.

---

### 4.5 — Race Condition: Cleanup Job vs Webhook Handler

State machine — valid transitions only:
```
PENDING → CONFIRMED  (webhook)
PENDING → EXPIRED    (cleanup job)
PENDING → CANCELLED  (admin)
EXPIRED / CANCELLED / CONFIRMED → (terminal or admin-only transition)
```

**Webhook handler (switch on status):**
```java
Booking booking = bookingRepo.findByIdForUpdate(bookingId); // SELECT FOR UPDATE
switch (booking.getStatus()) {
  case PENDING   -> confirmBooking(booking, payment);
  case EXPIRED   -> handleLateArrival(booking, payment); // auto-refund
  case CONFIRMED -> auditService.log(WEBHOOK_DUPLICATE, booking);
  case CANCELLED -> handleLateArrival(booking, payment);
}
```

**Cleanup job — both tables in same `@Transactional`:**
```java
List<Booking> expired = bookingRepo.findExpiredPendingForUpdate(LocalDateTime.now());
for (Booking b : expired) {
    b.setStatus(BookingStatus.EXPIRED);
    b.getSeat().setStatus(SeatStatus.AVAILABLE); // seat released in same TX
    auditService.log("BOOKING_EXPIRED", "system", b);
    auditService.log("SEAT_RELEASED",   "system", b.getSeat());
}
// JPA dirty tracking flushes atomically at TX commit
```

---

### 4.6 — Late Webhook Auto-Refund

```java
private void handleLateArrival(Booking booking, PaymentEvent payment) {
    auditService.log("WEBHOOK_LATE_ARRIVAL", "system", booking);
    paymentTxRepo.updateStatus(payment.getPaymentId(), PaymentStatus.REFUNDED);
    mockPaymentClient.refund(payment.getPaymentId()); // POST /refund/{id}
    auditService.log("REFUND_INITIATED", "system", booking);
}
```

---

### 4.7 — Admin UI (Angular)

Minimal 5th Angular page at `/admin` (ADMIN role guard):
- Table of PENDING bookings with "Reconcile" button per row
- Recent audit log entries

---

### 4.8 — @Async Thread Pool (Mock Payment Service)

```java
@Bean(name = "webhookExecutor")
public TaskExecutor webhookExecutor() {
    ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
    ex.setCorePoolSize(5);
    ex.setMaxPoolSize(10);
    ex.setQueueCapacity(100);
    ex.setRejectedExecutionHandler(new CallerRunsPolicy()); // backpressure
    ex.initialize();
    return ex;
}
```
README note: *"In production: persistent queue (Redis Streams, SQS) to survive restarts."*

---

### 4.9 — Error Response Format & DTOs

```json
// Error
{ "error": "SEAT_UNAVAILABLE", "message": "Seat A1 is held by another user", "timestamp": "..." }

// GET /api/seats
[{ "id": "uuid", "label": "A1", "status": "AVAILABLE" }]

// POST /api/bookings
{ "bookingId": "uuid", "seatId": "uuid", "seatLabel": "A1",
  "status": "PENDING", "holdExpiresAt": "...", "idempotencyKey": "..." }

// POST /api/bookings/{id}/payment
{ "paymentId": "pay_uuid", "bookingId": "uuid", "status": "PENDING" }
```

Error codes: `SEAT_UNAVAILABLE` (409), `BOOKING_NOT_FOUND` (404), `BOOKING_NOT_OWNED` (403),
`BOOKING_EXPIRED` (409), `BOOKING_ALREADY_PAID` (409), `INVALID_WEBHOOK_SIGNATURE` (400), `UNAUTHORIZED` (401)

---

### 4.10 — HMAC vs mTLS for Webhook Authentication

**Why HMAC and not mTLS?**

| Aspect | HMAC-SHA256 | mTLS |
|---|---|---|
| What it proves | "Request was signed with our shared secret" | "Client presents a valid certificate from our CA" |
| Setup complexity | Shared secret in `.env` | CA, cert generation, cert rotation, Spring config |
| Failure mode | Leaked secret → rotate | Expired cert → service outage |
| Industry pattern | ✅ Used by Stripe, PayPal, GitHub, Twilio | ✅ Used for internal microservice mesh |
| Best fit for | **Third-party webhook auth** (gateway → your app) | **Internal service-to-service** (within your platform) |

**Decision: HMAC for mock payment → backend webhook.**

Rationale: The mock payment service *simulates a third-party gateway*. Stripe, PayPal, and every major real payment gateway use HMAC signatures for their webhooks — not mTLS. Choosing HMAC mirrors the exact production pattern you would use with a real gateway.

mTLS is the right choice for *internal* service-to-service (e.g. backend → audit microservice in a service mesh). README will note: *"If the payment service were an internal microservice (not a third-party gateway), mTLS via Istio/Envoy would be preferred. HMAC is chosen here because it mirrors the Stripe webhook authentication model."*

---

### 4.11 — Redis Cache for Seat Status & Idempotency Layer (Write-Through Pattern)

**Problem:** 100 users polling `/api/seats` every 1 second = 100 DB queries/sec with no cache. 

**Solution (Pattern 1 — Event-Driven / Write-Through Caching):**
Rather than passive cache expiration using a short TTL, we implement a write-through pattern using individual keys. This guarantees 100% real-time accuracy and zero stale reads without hitting the DB on reads.

1. **Granular Caching with Individual Keys:**
   * Each seat is stored as its own key: `seat:cache:<id>`.
   * **Reading (`GET /api/seats`):** The set of seats is static (A1, A2, A3). We run `MGET` for the 3 seat keys: `seat:cache:A1`, `seat:cache:A2`, `seat:cache:A3`.
   * **Lazy Loading:** For any key that returns `null` (not yet cached), we fetch that seat's data from the DB, cache it in Redis with a long safety TTL (e.g., 24 hours), and return it.
   * **Writing / On-Demand Updates:** Whenever a seat status changes in the database (hold created, payment confirmed, hold expired), we update that specific seat's cache key inside the same transaction or right after it:
     `redisTemplate.opsForValue().set("seat:cache:" + seat.getId(), SeatResponse.from(seat), 24, TimeUnit.HOURS)`

2. **Redis Pre-Checks (First Layer Defense):**
   * **Seat Availability Pre-Check:** Before attempting a seat hold (before acquiring the distributed lock or querying the DB), check `seat:cache:<id>` in Redis. If it is already `HELD` or `RESERVED`, fail fast immediately with a `SeatUnavailableException` (saving DB CPU).
   * **Idempotency Pre-Check:**
     - **In-flight requests:** Use a short-lived `SETNX` lock `idempotency:lock:<key>` (e.g., 10s) to block concurrent duplicate submissions.
     - **Completed bookings:** Cache the final `BookingResponse` under `idempotency:key:<key>` (2-hour TTL). If a request matches, return it immediately without hitting the DB.

```java
// business/service/BookingService.java or web/controller/BookingController.java

public BookingResponse createBooking(BookingCommand cmd) {
    // 1. Idempotency Pre-Check (Layer 0.5)
    BookingResponse cachedBooking = (BookingResponse) redisTemplate.opsForValue()
        .get("idempotency:key:" + cmd.getIdempotencyKey());
    if (cachedBooking != null) {
        return cachedBooking;
    }

    // Acquire short-lived idempotency lock to prevent double-submits
    Boolean lockAcquired = redisTemplate.opsForValue()
        .setIfAbsent("idempotency:lock:" + cmd.getIdempotencyKey(), "PROCESSING", 10, TimeUnit.SECONDS);
    if (Boolean.FALSE.equals(lockAcquired)) {
        throw new DuplicateRequestException("Request is currently being processed.");
    }

    try {
        // 2. Seat Status Pre-Check (Layer 0.8)
        SeatResponse cachedSeat = (SeatResponse) redisTemplate.opsForValue()
            .get("seat:cache:" + cmd.getSeatId());
        if (cachedSeat != null && cachedSeat.status() != SeatStatus.AVAILABLE) {
            throw new SeatUnavailableException("Seat is already taken.");
        }

        // 3. Proceed to Distributed Lock + Database Transaction (optimistic/pessimistic lock)
        BookingResponse response = runInDatabaseTransaction(cmd);

        // 4. Overwrite Redis cache write-through
        redisTemplate.opsForValue().set("seat:cache:" + cmd.getSeatId(), 
            SeatResponse.from(response.getSeat()), 24, TimeUnit.HOURS);
        redisTemplate.opsForValue().set("idempotency:key:" + cmd.getIdempotencyKey(), 
            response, 2, TimeUnit.HOURS);

        return response;
    } finally {
        redisTemplate.delete("idempotency:lock:" + cmd.getIdempotencyKey());
    }
}
```

---

### 4.12 — Concurrency Test Suite

A dedicated integration test that proves the concurrency strategy works.

**File:** `backend/src/test/java/com/linkz/seatreservation/concurrency/ConcurrentBookingTest.java`

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
class ConcurrentBookingTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Test
    void only_one_of_100_concurrent_requests_should_succeed() throws Exception {
        String seatId = seedOneSeat();
        int threadCount = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger conflicts = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            String userId = "user-" + i;
            pool.submit(() -> {
                ready.countDown();
                start.await(); // all threads start simultaneously
                ResponseEntity<BookingResponse> resp = bookSeat(seatId, userId);
                if (resp.getStatusCode() == HttpStatus.CREATED)     successes.incrementAndGet();
                if (resp.getStatusCode() == HttpStatus.CONFLICT)    conflicts.incrementAndGet();
            });
        }

        ready.await();   // wait for all threads to be ready
        start.countDown(); // fire!
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(successes.get()).isEqualTo(1);          // exactly 1 winner
        assertThat(conflicts.get()).isEqualTo(99);         // 99 clean failures
        assertThat(getSeat(seatId).status()).isEqualTo("HELD"); // seat is HELD
    }

    @Test
    void same_idempotency_key_returns_same_booking() throws Exception {
        String seatId = seedOneSeat();
        String idempotencyKey = UUID.randomUUID().toString();

        BookingResponse first  = bookSeat(seatId, "user-1", idempotencyKey).getBody();
        BookingResponse second = bookSeat(seatId, "user-1", idempotencyKey).getBody();

        assertThat(first.bookingId()).isEqualTo(second.bookingId()); // same booking returned
    }

    @Test
    void webhook_processed_exactly_once_when_delivered_twice() throws Exception {
        // ... send same webhook event_id twice, verify only one BOOKING_CONFIRMED in audit log
    }

    @Test
    void expired_hold_releases_seat_back_to_available() throws Exception {
        // ... book seat, fast-forward time or set hold_expires_at in past, run cleanup job
        // verify seat is AVAILABLE
    }
}
```

**Test infrastructure:**
- **Testcontainers**: real PostgreSQL + Redis — no mocking, true integration
- **CountDownLatch**: guarantees all 100 threads start at the exact same millisecond
- **Assertions**: exactly 1 success, 99 `SEAT_UNAVAILABLE` conflicts, seat in HELD state

---

## 5. Architecture: Hexagonal (Ports & Adapters)

### Why Hexagonal?
- Domain logic has **zero Spring/JPA dependencies** → pure unit testable
- Ports are interfaces → adapters are swappable (e.g. swap JPA for jOOQ, swap Redis for Hazelcast)
- Concurrency tests test the domain use cases directly, no HTTP needed
- Shows senior engineering maturity: intentional separation of concerns

### Package Structure

```
com.linkz.seatreservation/
│
├── business/                            # Core — NO Spring, NO JPA, NO framework dependency
│   │
│   ├── domain/                          # Pure Java domain models
│   │   ├── model/
│   │   │   ├── Seat.java                # id, label, SeatStatus, version
│   │   │   ├── Booking.java             # id, userId, seatId, BookingStatus, holdExpiresAt
│   │   │   ├── Payment.java             # id, bookingId, externalPaymentId, PaymentStatus
│   │   │   └── AuditEntry.java          # actor, action, entityType, entityId, before, after
│   │   ├── enums/
│   │   │   ├── SeatStatus.java          # AVAILABLE | HELD | RESERVED
│   │   │   ├── BookingStatus.java       # PENDING | CONFIRMED | EXPIRED | CANCELLED
│   │   │   └── PaymentStatus.java       # PENDING | SUCCESS | FAILED | REFUNDED
│   │   └── exception/
│   │       ├── SeatUnavailableException.java
│   │       ├── BookingNotFoundException.java
│   │       └── BookingNotOwnedException.java
│   │
│   ├── port/
│   │   ├── in/                          # Inbound port interfaces — what web calls
│   │   │   ├── HoldSeatUseCase.java             # holdSeat(cmd) → BookingResult
│   │   │   ├── InitiatePaymentUseCase.java      # initiatePayment(cmd) → PaymentResult
│   │   │   ├── HandleWebhookUseCase.java        # handleWebhook(event) → void
│   │   │   └── ReconcilePaymentUseCase.java     # reconcile(bookingId) → void
│   │   └── out/                         # Outbound port interfaces — what business needs
│   │       ├── SeatRepositoryPort.java          # findByIdForUpdate, updateStatus
│   │       ├── BookingRepositoryPort.java       # save, findByIdForUpdate, findExpiredPending
│   │       ├── PaymentRepositoryPort.java       # save, updateStatus
│   │       ├── WebhookEventRepositoryPort.java  # save, existsByEventId
│   │       ├── PaymentGatewayPort.java          # initiatePayment, refund, queryStatus
│   │       ├── DistributedLockPort.java         # tryLock(key, waitMs, ttlMs), unlock(key)
│   │       ├── CachePort.java                   # get(key), put(key, value, ttl), evict(key)
│   │       └── AuditPort.java                   # log(AuditEntry)
│   │
│   └── service/                         # Use case implementations (only depend on ports)
│       ├── BookingService.java           # implements HoldSeatUseCase
│       ├── PaymentService.java           # implements InitiatePaymentUseCase
│       ├── WebhookService.java           # implements HandleWebhookUseCase
│       └── ReconciliationService.java    # implements ReconcilePaymentUseCase
│
├── web/                                 # Port IN — inbound adapters (drive the business)
│   ├── controller/
│   │   ├── SeatController.java          # GET /api/seats → calls CachePort then SeatRepositoryPort
│   │   ├── BookingController.java       # POST /api/bookings → calls HoldSeatUseCase
│   │   ├── PaymentController.java       # POST /api/bookings/{id}/payment → InitiatePaymentUseCase
│   │   ├── WebhookController.java       # POST /api/webhooks/payment → HandleWebhookUseCase
│   │   └── AdminController.java         # /api/admin/* → ReconcilePaymentUseCase + repos
│   ├── scheduler/
│   │   ├── SeatHoldCleanupJob.java      # @Scheduled — calls BookingService directly
│   │   └── ReconciliationJob.java       # @Scheduled — calls ReconcilePaymentUseCase
│   └── dto/
│       ├── request/
│       │   ├── HoldSeatRequest.java
│       │   └── InitiatePaymentRequest.java
│       └── response/
│           ├── SeatResponse.java
│           ├── BookingResponse.java
│           ├── PaymentResponse.java
│           └── ErrorResponse.java
│
└── adapter/                             # Port OUT — outbound adapters (implement business ports)
    ├── persistence/                     # PostgreSQL via Spring Data JPA
    │   ├── SeatJpaAdapter.java          # implements SeatRepositoryPort
    │   ├── BookingJpaAdapter.java       # implements BookingRepositoryPort
    │   ├── PaymentJpaAdapter.java       # implements PaymentRepositoryPort
    │   ├── WebhookEventJpaAdapter.java  # implements WebhookEventRepositoryPort
    │   ├── AuditJpaAdapter.java         # implements AuditPort
    │   └── entity/                      # JPA @Entity — infrastructure detail only
    │       ├── SeatEntity.java
    │       ├── BookingEntity.java
    │       ├── PaymentTransactionEntity.java
    │       ├── WebhookEventEntity.java
    │       └── AuditLogEntity.java
    ├── lock/
    │   └── RedissonLockAdapter.java     # implements DistributedLockPort via Redisson
    ├── cache/
    │   └── RedisCacheAdapter.java       # implements CachePort via Spring Cache + Redis
    └── payment/
        └── MockPaymentGatewayAdapter.java # implements PaymentGatewayPort via HTTP
```

**Key rule:** Dependencies only flow inward.
```
web  →  business  ←  adapter
         ↑
     (no outward deps)
```
- `web` knows about `business.port.in` (use cases) — never about `adapter`
- `adapter` knows about `business.port.out` (port interfaces) — never about `web`
- `business` knows nothing about `web` or `adapter` — zero framework imports

---

## 6. Full Project Structure

```
seat-reservation/
├── IMPLEMENTATION_PLAN.md
│
├── backend/
│   ├── src/main/java/com/linkz/seatreservation/
│   │   │
│   │   ├── business/                    # Domain + ports + services (zero framework deps)
│   │   │   ├── domain/model/            # Seat, Booking, Payment, AuditEntry
│   │   │   ├── domain/enums/            # SeatStatus, BookingStatus, PaymentStatus
│   │   │   ├── domain/exception/        # Domain exceptions
│   │   │   ├── port/in/                 # HoldSeatUseCase, InitiatePaymentUseCase, ...
│   │   │   ├── port/out/                # SeatRepositoryPort, PaymentGatewayPort, ...
│   │   │   └── service/                 # BookingService, PaymentService, WebhookService, ...
│   │   │
│   │   ├── web/                         # Inbound adapters (port IN)
│   │   │   ├── controller/              # SeatController, BookingController, PaymentController,
│   │   │   │                            # WebhookController, AdminController
│   │   │   ├── scheduler/               # SeatHoldCleanupJob, ReconciliationJob
│   │   │   └── dto/                     # Request & Response DTOs, ErrorResponse
│   │   │
│   │   └── adapter/                     # Outbound adapters (port OUT)
│   │       ├── persistence/             # SeatJpaAdapter, BookingJpaAdapter, ...
│   │       │   └── entity/              # JPA @Entity classes
│   │       ├── lock/                    # RedissonLockAdapter
│   │       ├── cache/                   # RedisCacheAdapter
│   │       └── payment/                 # MockPaymentGatewayAdapter
│   │
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── db/migration/
│   │       ├── V1__create_seats.sql
│   │       ├── V2__create_bookings.sql
│   │       ├── V3__create_payment_transactions.sql
│   │       ├── V4__create_webhook_events.sql
│   │       ├── V5__create_audit_logs.sql
│   │       └── V6__seed_seats.sql
│   │
│   ├── src/test/java/com/linkz/seatreservation/
│   │   ├── concurrency/
│   │   │   └── ConcurrentBookingTest.java    # 100-thread Testcontainers integration test
│   │   ├── business/
│   │   │   ├── BookingServiceTest.java        # Pure unit test — no Spring context
│   │   │   └── WebhookServiceTest.java        # Pure unit test — no Spring context
│   │   └── web/
│   │       └── WebhookControllerTest.java     # HMAC signature verification test
│   │
│   ├── build.gradle
│   └── Dockerfile
│
├── frontend/
│   ├── src/app/
│   │   ├── core/
│   │   │   ├── auth/
│   │   │   │   ├── auth.guard.ts
│   │   │   │   ├── admin.guard.ts
│   │   │   │   └── auth.interceptor.ts
│   │   │   └── services/
│   │   │       ├── seat.service.ts         # polls every 1s
│   │   │       ├── booking.service.ts
│   │   │       └── payment.service.ts
│   │   └── pages/
│   │       ├── login/
│   │       ├── seats/
│   │       ├── payment/
│   │       ├── confirmation/
│   │       └── admin/
│   ├── Dockerfile
│   └── nginx.conf
│
├── mock-payment-service/
│   └── src/main/java/com/linkz/mockpayment/
│       ├── PaymentController.java
│       ├── RefundController.java
│       ├── WebhookDeliveryService.java
│       └── SimulationController.java
│
├── keycloak/
│   └── realm-export.json
│
├── docker-compose.yml
├── .env.example
└── README.md
```

---

## 7. Database Schema

```sql
-- V1: Seats
CREATE TABLE seats (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  label      VARCHAR(10) NOT NULL UNIQUE,
  status     VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',  -- AVAILABLE | HELD | RESERVED
  version    BIGINT NOT NULL DEFAULT 0,                 -- JPA @Version
  updated_at TIMESTAMP DEFAULT NOW()
);

-- V2: Bookings
CREATE TABLE bookings (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id          VARCHAR(255) NOT NULL,
  seat_id          UUID NOT NULL REFERENCES seats(id),
  status           VARCHAR(20) NOT NULL,                -- PENDING | CONFIRMED | EXPIRED | CANCELLED
  idempotency_key  VARCHAR(255) NOT NULL,
  hold_expires_at  TIMESTAMP NOT NULL,                  -- retained after CONFIRMED (harmless, see trade-offs)
  created_at       TIMESTAMP DEFAULT NOW(),
  updated_at       TIMESTAMP DEFAULT NOW()
);

-- Partial index: only one ACTIVE booking per idempotency_key
-- EXPIRED/CANCELLED excluded → rebooking after expiry works
CREATE UNIQUE INDEX uq_one_active_booking
  ON bookings (idempotency_key)
  WHERE status IN ('PENDING', 'CONFIRMED');

-- V3: Payment Transactions
CREATE TABLE payment_transactions (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  booking_id           UUID NOT NULL REFERENCES bookings(id),
  external_payment_id  VARCHAR(255),
  amount               NUMERIC(10,2) NOT NULL,
  status               VARCHAR(20) NOT NULL,            -- PENDING | SUCCESS | FAILED | REFUNDED
  raw_payload          TEXT,
  created_at           TIMESTAMP DEFAULT NOW(),
  updated_at           TIMESTAMP DEFAULT NOW()
);

-- V4: Webhook Events (idempotency store)
CREATE TABLE webhook_events (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  provider     VARCHAR(50) NOT NULL,
  event_id     VARCHAR(255) NOT NULL UNIQUE,
  raw_payload  TEXT NOT NULL,
  status       VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
  processed_at TIMESTAMP,
  error        TEXT,
  created_at   TIMESTAMP DEFAULT NOW()
);

-- V5: Audit Logs (append-only — NEVER UPDATE or DELETE)
CREATE TABLE audit_logs (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  actor        VARCHAR(255) NOT NULL,
  action       VARCHAR(100) NOT NULL,
  entity_type  VARCHAR(50) NOT NULL,
  entity_id    VARCHAR(255) NOT NULL,
  before_state JSONB,
  after_state  JSONB,
  created_at   TIMESTAMP DEFAULT NOW()
);

-- V6: Seed data
INSERT INTO seats (label) VALUES ('A1'), ('A2'), ('A3');
```

---

## 8. API Design

### Backend REST API (port 8080)

| Method | Path | Auth | Cache | Description |
|---|---|---|---|---|
| GET | `/api/seats` | ✅ JWT | ✅ Redis (Write-Through) | List 3 seats (cache-first) |
| POST | `/api/bookings` | ✅ JWT | ❌ Evicts cache | Hold seat (Redis lock → DB lock → idempotent) |
| POST | `/api/bookings/{id}/payment` | ✅ JWT | ❌ | Initiate payment |
| POST | `/api/webhooks/payment` | 🔐 HMAC | ❌ Evicts cache | Receive webhook |
| GET | `/api/admin/pending-bookings` | ✅ JWT + ADMIN | ❌ | List PENDING bookings |
| POST | `/api/admin/reconcile/{id}` | ✅ JWT + ADMIN | ❌ | Manual payment re-fetch |
| GET | `/api/admin/audit-logs` | ✅ JWT + ADMIN | ❌ | Query audit trail |

### Mock Payment Service (port 9090)

| Method | Path | Description |
|---|---|---|
| POST | `/pay` | Accept payment, return `paymentId`, async webhook |
| GET | `/payment/{id}` | Query status (reconciliation) |
| POST | `/refund/{id}` | Mark as REFUNDED (late webhook auto-refund) |
| POST | `/simulate/fail` | Next webhook = failure |
| POST | `/simulate/delay` | Next webhook delayed 60s |

---

## 9. Agents & Phases

| Phase | Agent | Key Deliverables |
|---|---|---|
| **1** | scaffold-agent | Gradle multi-project, `docker-compose.yml` (+ Redis), Dockerfiles, hexagonal folder scaffold |
| **2** | db-agent | V1–V6 Flyway migrations, partial index, seed data |
| **3** | auth-agent | `realm-export.json`, `SecurityConfig.java`, Angular Keycloak adapter, guards |
| **4** | booking-agent | Domain model, ports, `BookingService` (Redis lock → DB lock), hold TTL, idempotency |
| **5** | payment-agent | Mock payment service (Docker), webhook delivery, retry, refund endpoint |
| **6** | payment-agent | `WebhookService` (state machine), `ReconciliationService`, HMAC verification |
| **7** | audit-agent | `AuditService`, `AuditJpaAdapter`, all 18 event types wired |
| **8** | cache-agent | `RedisCacheAdapter`, `CachePort`, `@Cacheable`/`@CacheEvict` on seat endpoints |
| **9** | frontend-agent | 5 Angular pages, JWT interceptor, 1s polling, admin page |
| **10** | test-agent | `ConcurrentBookingTest` (Testcontainers), `BookingServiceTest` (unit), webhook tests |
| **11** | docs-agent | `README.md`, `.env.example`, architecture diagram, trade-off docs |

---

## 10. Key Trade-offs (for README)

| Decision | Rationale |
|---|---|
| Keycloak vs Firebase/Clerk | Self-hosted fits Docker Compose; same principle. Production: cloud IdP, zero code change |
| HMAC vs mTLS for webhook | HMAC mirrors Stripe/PayPal pattern (third-party gateway auth). mTLS = internal service mesh. Both valid in their context |
| Redis lock as Layer 1 | Fast-fail 99% of contention at Redis level; DB lock remains correctness guarantee if Redis fails |
| DB SELECT FOR UPDATE as Layer 2 | Correctness guarantee regardless of Redis health. Defence-in-depth |
| Partial unique index (not table constraint) | Allows rebooking after expiry; table UNIQUE would permanently block reuse of same key |
| `hold_expires_at` retained after CONFIRMED | Inert, harmless; cleanup job filters by status. Gives audit trail of original hold window |
| Write-Through Redis Cache | Reads seats directly from Redis without hitting the DB, updating on-demand on writes |
| 1s polling with Write-Through cache | Guarantees 100% real-time data consistency and zero database load |
| Hexagonal architecture | Domain logic zero-dependency → pure unit testable; adapters swappable |
| Mock payment service (lightweight Spring Boot) | Demonstrates full webhook reliability pattern; HMAC mirrors real gateway behaviour |
| Cleanup job: bookings + seats atomically | Same @Transactional — partial state (booking EXPIRED, seat still HELD) is impossible |
| Auto-refund on late webhook | Prevents user losing money when payment succeeds after booking expires |
| Bounded @Async thread pool | Prevents thread explosion; CallerRunsPolicy provides backpressure |
| Concurrency integration test (Testcontainers) | Proves 100-concurrent-request claim with real DB+Redis — not a mock |
| Scheduled reconciliation (not Kafka) | Simpler to operate at this scale; Kafka preferred at production scale |
| Admin Angular page (not curl-only) | Makes reconciliation usable by reviewer; shows operational awareness |

---

## 11. Port Layout

| Service | Port |
|---|---|
| Frontend (Angular) | 4200 |
| Backend (Spring Boot) | 8080 |
| Keycloak | 8180 |
| PostgreSQL | 5432 |
| Redis | 6379 |
| Mock Payment Service | 9090 |

---

## 12. Progress Tracker

- [ ] Phase 1  — Project Scaffold (Gradle multi-project + Docker Compose + Redis)
- [ ] Phase 2  — Database Schema & Migrations (Flyway V1–V6 + partial index)
- [ ] Phase 3  — Auth (Keycloak realm + Spring Security + Angular adapter + guards)
- [ ] Phase 4  — Seat & Booking Domain (hexagonal ports + Redis lock + DB lock + idempotency)
- [ ] Phase 5  — Mock Payment Service (async webhooks + retry + refund)
- [ ] Phase 6  — Webhook Handler & Reconciliation (state machine + HMAC)
- [ ] Phase 7  — Audit Log (18 event types, all wired)
- [ ] Phase 8  — Redis Cache (Write-Through seat cache, on-demand updates)
- [ ] Phase 9  — Angular UI (5 pages, 1s polling)
- [ ] Phase 10 — Concurrency Tests (Testcontainers, 100-thread, idempotency, webhook)
- [ ] Phase 11 — README & Documentation
