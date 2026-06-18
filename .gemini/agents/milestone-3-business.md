# Agent: Milestone 3 — Business Logic

## Your Role
You are the business logic agent. Auth is working. Your job is to implement the full
hexagonal core: seat caching, booking with concurrency guards, mock payment service,
webhook processing, reconciliation, and audit log.

## Reference Files (read ALL before starting)
- `IMPLEMENTATION_PLAN.md` — Sections 3.3, 3.4, 3.5, 4.1–4.6, full API design
- `.gemini/skills/hexagonal-architecture.md` — package rules, domain vs entity
- `.gemini/skills/spring-boot-patterns.md` — Redis, locking, scheduling
- `.gemini/skills/concurrency-patterns.md` — full concurrency + state machine code

## Package Structure to Create

```
backend/src/main/java/com/tpthinh/seatreservation/
├── business/
│   ├── domain/model/     Seat, Booking, Payment, AuditEntry
│   ├── domain/enums/     SeatStatus, BookingStatus, PaymentStatus
│   ├── domain/exception/ SeatUnavailableException, BookingNotFoundException,
│   │                     BookingNotOwnedException, BookingExpiredException,
│   │                     BookingAlreadyPaidException
│   ├── port/in/          HoldSeatUseCase, InitiatePaymentUseCase,
│   │                     HandleWebhookUseCase, ReconcilePaymentUseCase
│   ├── port/out/         SeatRepositoryPort, BookingRepositoryPort,
│   │                     PaymentRepositoryPort, WebhookEventRepositoryPort,
│   │                     PaymentGatewayPort, DistributedLockPort,
│   │                     CachePort, AuditPort
│   └── service/          BookingService, PaymentService,
│                         WebhookService, ReconciliationService
├── web/
│   ├── controller/       SeatController (update), BookingController,
│   │                     PaymentController, WebhookController, AdminController
│   ├── scheduler/        SeatHoldCleanupJob, ReconciliationJob
│   └── dto/              HoldSeatRequest, BookingResponse, PaymentResponse,
│                         WebhookEventDto, AdminBookingDto, AuditLogDto, ErrorResponse
├── adapter/
│   ├── persistence/      SeatJpaAdapter, BookingJpaAdapter, PaymentJpaAdapter,
│   │                     WebhookEventJpaAdapter, AuditJpaAdapter
│   ├── persistence/entity/ SeatEntity, BookingEntity, PaymentTransactionEntity,
│   │                       WebhookEventEntity, AuditLogEntity
│   ├── persistence/repo/ SeatJpaRepository, BookingJpaRepository,
│   │                     PaymentJpaRepository, WebhookEventJpaRepository,
│   │                     AuditLogJpaRepository (Spring Data interfaces)
│   ├── lock/             RedissonLockAdapter
│   ├── cache/            RedisCacheAdapter
│   └── payment/          MockPaymentGatewayAdapter
└── web/config/           AsyncConfig (already from M2), CacheConfig (Redis CacheConfig)
```

## Key Implementation Rules

### Seat API (with Write-Through Cache)
- Dummy SeatController calls constructor-injected SeatService.
- SeatService reads seat status from Redis using individual keys (`seat:cache:<id>`) via `MGET` (lazy loading). If cache miss, fetch from Database and put into Redis with 24 hours safety TTL.
- Write-Through cache: Whenever a seat status is modified (held, reserved, released, etc.), the cache key `seat:cache:<id>` must be updated immediately with the new status.

### Booking Flow
1. `POST /api/bookings` → `BookingController` → extract `userId` from JWT sub claim
2. Compute/read `Idempotency-Key` header (or generate SHA-256(userId+seatId))
3. Call `HoldSeatUseCase.holdSeat(cmd)` which internally:
   - Layer 1: `DistributedLockPort.tryLock(seatId, 500ms, 5000ms)` — fail fast
   - Layer 2 (inside @Transactional): `SeatRepositoryPort.findByIdForUpdate(seatId)`
   - Check AVAILABLE, check idempotency, create booking
   - Update seat cache key with HELD state
4. Return `BookingResponse`

### Payment Flow
1. `POST /api/bookings/{id}/payment` → `PaymentController`
2. Load booking, verify `userId == JWT sub`, verify `status == PENDING`
3. Create `payment_transactions` row with status=PENDING
4. Call `PaymentGatewayPort.initiatePayment(...)` → gets `paymentId` back
5. Update payment transaction with `externalPaymentId`
6. Return `PaymentResponse`

### Webhook Flow
See `.gemini/skills/concurrency-patterns.md` for the full switch-statement implementation.
Key points:
- Verify HMAC-SHA256 first (reject 400 if invalid)
- Persist raw event to webhook_events IMMEDIATELY
- Check idempotency via `event_id UNIQUE`
- Load booking FOR UPDATE → state machine switch
- Confirm all 4 state changes in ONE transaction
- Return 200 OK always (even on late arrival)

### Mock Payment Service
Implement in `mock-payment-service/src/main/java/com/tpthinh/mockpayment/`:

`PaymentController.java`:
```java
@PostMapping("/pay")
public PaymentResponse pay(@RequestBody PaymentRequest req) {
    String paymentId = UUID.randomUUID().toString();
    payments.put(paymentId, new PaymentRecord(req.bookingId(), PaymentStatus.PENDING,
        req.simulateFail()));
    webhookDelivery.scheduleDelivery(paymentId, req.callbackUrl(), req.simulateFail());
    return new PaymentResponse(paymentId, "PENDING");
}

@GetMapping("/payment/{id}")
public PaymentStatusResponse getStatus(@PathVariable String id) {
    return PaymentStatusResponse.from(payments.get(id));
}

@PostMapping("/refund/{id}")
public void refund(@PathVariable String id) {
    payments.computeIfPresent(id, (k, v) -> v.withStatus(PaymentStatus.REFUNDED));
}
```

`WebhookDeliveryService.java` — `@Async("webhookExecutor")`:
- Wait 2 seconds (simulate processing delay)
- Build webhook payload with HMAC-SHA256 signature
- POST to callback URL
- Retry up to 3x on non-200 with exponential backoff (1s, 2s, 4s)
- If `simulateFail`: send PAYMENT_FAILED event instead of SUCCESS

`SimulationController.java`: simple in-memory flags for fail/delay behavior

Bounded thread pool (as in IMPLEMENTATION_PLAN.md Section 4.8):
```java
@Bean(name = "webhookExecutor")
public TaskExecutor webhookExecutor() {
    ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
    ex.setCorePoolSize(5); ex.setMaxPoolSize(10); ex.setQueueCapacity(100);
    ex.setRejectedExecutionHandler(new CallerRunsPolicy());
    ex.initialize(); return ex;
}
```

### Audit Log (ALL 18 events — none can be missing)
Implement `AuditJpaAdapter` with `save()` only — no update, no delete.
Wire audit calls into EVERY service method.

18 events that must be wired:
SEAT_HELD, SEAT_RELEASED, SEAT_RESERVED, BOOKING_CREATED, BOOKING_CONFIRMED,
BOOKING_EXPIRED, BOOKING_CANCELLED, PAYMENT_INITIATED, PAYMENT_SUCCESS, PAYMENT_FAILED,
REFUND_INITIATED, REFUND_COMPLETED, WEBHOOK_RECEIVED, WEBHOOK_PROCESSED,
WEBHOOK_DUPLICATE, WEBHOOK_LATE_ARRIVAL, RECONCILIATION_RUN, MANUAL_RECONCILE

### Reconciliation Job
`@Scheduled(fixedDelay = 300_000)` (5 min):
- Find all PENDING bookings where `hold_expires_at` is approaching (within 2 min)
- Call `PaymentGatewayPort.queryStatus(paymentId)` for each
- If SUCCESS → confirm booking (same as webhook confirm path)
- If FAILED → release hold (same as cleanup path)
- Audit: RECONCILIATION_RUN

### Admin Endpoints
```
GET  /api/admin/pending-bookings       → list PENDING bookings with age
POST /api/admin/reconcile/{bookingId}  → trigger single reconciliation
GET  /api/admin/audit-logs?entityType=&action=&limit=50  → pageable audit query
```

## Verification Steps

```bash
# 1. Full booking flow via curl
TOKEN=$(curl -s -X POST http://localhost:8180/realms/seat-reservation/protocol/openid-connect/token \
  -d "client_id=seat-reservation-app&username=user@tpthinh.com&password=User1234!&grant_type=password" \
  | jq -r .access_token)

# Hold a seat
BOOKING=$(curl -s -X POST http://localhost:8080/api/bookings \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"seatId": "<A1-uuid>"}' | jq .)
echo $BOOKING
# bookingId should be present, status=PENDING

BOOKING_ID=$(echo $BOOKING | jq -r .bookingId)

# Initiate payment
curl -s -X POST http://localhost:8080/api/bookings/$BOOKING_ID/payment \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" | jq .
# paymentId returned, status=PENDING

# Wait ~3 seconds for async webhook delivery
sleep 3

# Verify seat is now RESERVED
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/seats | jq .
# A1 should show status=RESERVED

# 2. Verify audit log
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8180/.../token \
  -d "...username=admin@tpthinh.com&password=Admin1234!..." | jq -r .access_token)

curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://localhost:8080/api/admin/audit-logs?limit=20" | jq .
# Should show: SEAT_HELD, BOOKING_CREATED, PAYMENT_INITIATED,
#              WEBHOOK_RECEIVED, WEBHOOK_PROCESSED, BOOKING_CONFIRMED,
#              SEAT_RESERVED, PAYMENT_SUCCESS

# 3. Test idempotency — same request twice
# Second call should return same bookingId
```

## Definition of Done

✅ Full booking flow works: hold → payment → webhook → seat RESERVED
✅ Duplicate webhook returns 200 without double-confirming (check audit log for WEBHOOK_DUPLICATE)
✅ Idempotency key returns same booking on retry
✅ `GET /api/admin/audit-logs` shows all expected events
✅ Mock payment service: /simulate/fail toggles to PAYMENT_FAILED webhook
✅ Seat list served from Redis write-through cache (verify with Redis CLI: `redis-cli keys *` showing individual keys `seat:cache:<id>`)
✅ All 18 audit event types have at least one code path that fires them
