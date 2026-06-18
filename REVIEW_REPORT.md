# Review Report
**Date:** 2026-06-18  
**Reviewer:** reviewer-agent  
**Scope:** Full codebase review against IMPLEMENTATION_PLAN.md + reviewer.md checklist  

---

## Architecture: PASS (with minor naming deviation)

- [x] No `import` from `web.*` or `adapter.*` inside `business.*` packages — all business/service Java files import from business.domain.*, business.port.*, and allowed utilities only
- [x] No `@Entity`, `@Component`, `@Repository` in `business/domain/` — domain model files are pure Java records (Seat, Booking, Payment, AuditEntry, enums, exceptions)
- [x] All port interfaces are in `business/port/in/` or `business/port/external/` — **deviation**: IMPLEMENTATION_PLAN.md specifies `business/port/out/` but code uses `business/port/external/`. Naming only; hexagonal integrity preserved.
- [x] All `@RestController` classes are in `web/controller/` — 5 controllers confirmed (SeatController, BookingController, PaymentController, WebhookController, AdminController)
- [x] All JPA `@Entity` classes are in `adapter/persistence/entity/` — 5 entities confirmed (SeatEntity, BookingEntity, PaymentTransactionEntity, WebhookEventEntity, AuditLogEntity)
- [x] `BookingService` constructor only takes port interfaces — `SeatRepositoryPort`, `BookingRepositoryPort`, `DistributedLockPort`, `CachePort`, `AuditPort`, `TransactionTemplate` — no Spring Data repos directly

**Note:** Business services import `org.springframework.stereotype.Service` and `TransactionTemplate`. These are lightweight Spring utilities (not JPA/web framework), and their use is a pragmatic compromise. Domain models themselves are fully framework-free.

---

## Concurrency: PASS

- [x] `BookingService.holdSeat()` acquires Redis distributed lock BEFORE calling `seatRepo.findByIdForUpdate` — confirmed: `lockPort.tryLock()` at line 69, `findByIdForUpdate` at line 77
- [x] `findByIdForUpdate` uses `@Lock(LockModeType.PESSIMISTIC_WRITE)` — confirmed in `SeatJpaRepository`
- [x] Cleanup job updates BOTH `booking.status` AND `seat.status` in same `@Transactional` — confirmed: `expireBooking()` in `ReconciliationService` saves both inside `transactionTemplate.executeWithoutResult()`
- [x] Redis lock is released in `finally` block — `lockPort.unlock()` is inside `try { ... } finally { unlock }` at lines 122-124 of BookingService
- [x] Idempotency key DB check is INSIDE the `@Transactional` — `bookingRepo.findByIdempotencyKey()` at line 84 is inside `transactionTemplate.execute()` callback

---

## Webhook Reliability: PARTIAL PASS

- [x] `webhook_events.event_id` has UNIQUE constraint in V4 migration — confirmed: `event_id VARCHAR(255) NOT NULL UNIQUE`
- [x] Webhook handler uses `SELECT FOR UPDATE` on booking — `findByExternalPaymentIdForUpdate` with `@Lock(PESSIMISTIC_WRITE)` confirmed
- [x] State machine covers ALL 4 cases: PENDING, CONFIRMED, EXPIRED, CANCELLED — switch covers all BookingStatus values in WebhookService
- [x] `confirmBooking()` updates booking + seat + payment_transaction in ONE transaction — all saves inside `transactionTemplate.executeWithoutResult()` confirmed
- [x] Late arrival auto-refund calls `PaymentGatewayPort.refund()` — confirmed in `handleLateArrival()`
- [ ] **ISSUE (HIGH)**: Raw payload is NOT persisted to `webhook_events` BEFORE business logic. `handleWebhook()` line 42 only calls `auditPort.log(WEBHOOK_RECEIVED)` — an audit entry, NOT a `webhook_events` row. The actual `webhookEventRepo.saveEvent()` is called AFTER business logic (inside confirmBooking, failBooking, handleLateArrival). If processing crashes mid-flight, the raw event is permanently lost.

---

## Audit Log: PASS (all 18 event types verified)

| # | Event Type | Location |
|---|---|---|
| 1 | `SEAT_HELD` | `BookingService.holdSeat()` |
| 2 | `SEAT_RELEASED` | `WebhookService.failBooking()`, `ReconciliationService.expireBooking()`, `failBooking()` |
| 3 | `SEAT_RESERVED` | `WebhookService.confirmBooking()`, `ReconciliationService.confirmBooking()` |
| 4 | `BOOKING_CREATED` | `BookingService.holdSeat()` |
| 5 | `BOOKING_CONFIRMED` | `WebhookService.confirmBooking()`, `ReconciliationService.confirmBooking()` |
| 6 | `BOOKING_EXPIRED` | `ReconciliationService.expireBooking()` |
| 7 | `BOOKING_CANCELLED` | `WebhookService.failBooking()`, `ReconciliationService.failBooking()` |
| 8 | `PAYMENT_INITIATED` | `PaymentService.initiatePayment()` |
| 9 | `PAYMENT_SUCCESS` | `WebhookService.confirmBooking()`, `ReconciliationService.confirmBooking()` |
| 10 | `PAYMENT_FAILED` | `WebhookService.failBooking()`, `ReconciliationService.expireBooking()`, `failBooking()` |
| 11 | `REFUND_INITIATED` | `WebhookService.handleLateArrival()` |
| 12 | `REFUND_COMPLETED` | `WebhookService.handleLateArrival()` |
| 13 | `WEBHOOK_RECEIVED` | `WebhookService.handleWebhook()` line 42 |
| 14 | `WEBHOOK_PROCESSED` | `WebhookService.confirmBooking()`, `failBooking()`, `handleLateArrival()` |
| 15 | `WEBHOOK_DUPLICATE` | `WebhookService.handleWebhook()` (pre-check) + confirmed case |
| 16 | `WEBHOOK_LATE_ARRIVAL` | `WebhookService.handleLateArrival()` |
| 17 | `RECONCILIATION_RUN` | `ReconciliationService.reconcilePendingBookings()` |
| 18 | `MANUAL_RECONCILE` | `ReconciliationService.reconcile()` |

- [x] `AuditLogJpaRepository` has NO `update()` or `delete()` methods — only `save()` and `queryLogs()` confirmed
- [x] Audit calls are inside the same `@Transactional` / `transactionTemplate` as the business operation — confirmed for all services

---

## Security: PASS

- [x] Webhook endpoint (`POST /api/webhooks/**`) is `permitAll()` in SecurityConfig — confirmed line 39
- [x] HMAC verification rejects on mismatch — `InvalidWebhookSignatureException` mapped to HTTP 400 in GlobalExceptionHandler
- [x] `MessageDigest.isEqual()` used for constant-time comparison — confirmed in `WebhookController` line 43
- [x] Admin endpoints have `hasRole("ADMIN")` check — `requestMatchers("/api/admin/**").hasRole("ADMIN")` confirmed in SecurityConfig
- [x] JWT `sub` claim used as `userId` — `jwt.getSubject()` confirmed in `BookingController` lines 44 and 70

**Minor concern (LOW):** `webhookSecret` is not validated against empty string on startup. An empty `WEBHOOK_SECRET` env var would make HMAC trivially bypassable.

---

## Database: FAIL

- [x] Partial unique index present in V2 migration as `CREATE UNIQUE INDEX` (not table constraint) — confirmed
- [x] `hold_expires_at` is NOT NULL — confirmed in V2 migration
- [x] `webhook_events.event_id` has UNIQUE constraint — confirmed in V4 migration
- [x] `audit_logs` has no FK on actor (allows "system" as string) — confirmed: `actor VARCHAR(255) NOT NULL` with no FK
- [ ] **ISSUE (HIGH)**: V6 seeds **9 seats** (A1-A9), NOT 3. V6 contains three INSERT statements, adding A4-A6 and A7-A9 beyond the required A1-A3. The IMPLEMENTATION_PLAN.md specifies exactly 3 seats. This breaks the 3-seat platform concept and the frontend 3-column grid.

---

## Cache: PASS

- [x] Redis keys are individual `seat:cache:<id>` — confirmed in all `cachePort.put()` calls
- [x] `SeatService` uses MGET (`cachePort.multiGet()`) to lazy-load, falling back to DB on cache miss — confirmed
- [x] Cache is write-through — seat cache updated in `BookingService`, `WebhookService`, and `ReconciliationService` on every state change
- [x] Redis seat cache has 24-hour safety TTL (`24 * 3600` seconds) — confirmed in all seat cache `put()` calls

---

## Frontend: PARTIAL PASS

- [x] `SeatsComponent` implements `OnInit, OnDestroy` with `clearInterval` in `ngOnDestroy` — confirmed
- [x] Hold countdown timer uses `holdExpiresAt` from booking response — confirmed in `PaymentComponent.updateCountdown()`
- [x] Admin route has `CanActivate` guard checking ADMIN role — `canActivate: [authGuard, adminGuard]` confirmed in `app.routes.ts`
- [x] Auth interceptor attaches token to ALL requests — `authInterceptor` adds header unconditionally when token present
- [ ] **ISSUE (MEDIUM)**: Polling interval is **60 seconds** (`setInterval(..., 60000)`), not 1 second. IMPLEMENTATION_PLAN.md, README, and trade-off docs all specify 1-second polling. The write-through cache trade-off justification breaks down at 60s intervals.
- [ ] **ISSUE (MEDIUM)**: `simulateFail` checkbox value is hardcoded to `false` in `paymentService.initiatePayment(this.booking!.bookingId, false)`. The checkbox `this.simulateFail` is not passed to the backend. Instead, the component directly calls `http.post('http://localhost:9090/simulate/fail')` with a hardcoded localhost URL that fails when running inside Docker without host-network mode.

---

## Tests: PASS

- [x] `ConcurrentBookingTest` uses `CountDownLatch` for true simultaneous start — `ready` and `start` latches confirmed (lines 140-141, 166-167)
- [x] `BookingServiceTest` has NO Spring annotations — pure Mockito mocks, no Spring context, class-level has no `@SpringBootTest` or `@ExtendWith`
- [x] `WebhookControllerTest` tests constant-time signature comparison (invalid sig -> 400) — confirmed with `testWebhook_invalidHmacSignature_shouldReturn400()` and `testWebhook_missingSignatureHeader_shouldReturn400()`
- [x] Test profile uses `@ActiveProfiles("test")` with `@DynamicPropertySource` from Testcontainers — confirmed in `ConcurrentBookingTest`

---

## README: PASS

- [x] `docker compose up --build -d` is the only command needed — Quick Start section confirmed
- [x] All 6 ports documented — Frontend :4200, Backend :8080, Keycloak :8180, PostgreSQL :5432, Redis :6379, Mock Payment :9090
- [x] Both user credentials listed — `user@linkz.com / User1234!` and `admin@linkz.com / Admin1234!`
- [x] Trade-offs section covers Keycloak, HMAC vs mTLS, Redis+DB lock, hexagonal — 16 trade-off items documented
- [x] Known limitations are honest — README and DEPLOYMENT.md mention mock payment in-memory state loss on restart

---

## DEPLOYMENT.md: PASS (accurate)

DEPLOYMENT.md is accurate and complete. All 6 service ports documented, both credentials listed, troubleshooting covers common failure modes (Keycloak startup race, Flyway dirty state, port conflicts).

**Minor inaccuracy (LOW):** "Running Tests" suggests `docker compose up postgres redis -d` before Testcontainers tests. Testcontainers manages its own ephemeral containers; this step is unnecessary and can mislead users.

---

## RECONCILIATION.md: PASS (accurate)

RECONCILIATION.md accurately describes the reconciliation job logic, state machine, audit events, and test instructions (both automated and manual curl steps).

**Minor inaccuracy (LOW):** The "Configuration" section shows `scheduling.hold-cleanup-cron` YAML properties that do not exist in `application.yml`. Schedulers use `@Scheduled(fixedDelay)` annotations, not configurable YAML cron values.

---

## Gaps Found

| # | Area | Issue | Risk | Fix |
|---|---|---|---|---|
| 1 | Database | V6 seeds 9 seats (A1-A9) instead of 3 (A1-A3 only) | HIGH | Remove extra INSERTs from V6; keep only `INSERT INTO seats (label) VALUES ('A1'), ('A2'), ('A3');` |
| 2 | Webhook | Raw payload not saved to `webhook_events` before business logic — a crash mid-processing permanently loses the raw event | HIGH | Call `webhookEventRepo.saveEvent(...)` as step 1 in `handleWebhook()`, before the idempotency check and transaction |
| 3 | Frontend | Polling interval is 60s, not 1s as documented in README and IMPLEMENTATION_PLAN.md | MEDIUM | Change `setInterval(..., 60000)` to `setInterval(..., 1000)` in `SeatsComponent.ngOnInit()` |
| 4 | Frontend | `simulateFail` hardcoded to `false`; direct `localhost:9090` URL breaks Docker networking | MEDIUM | Pass `this.simulateFail` to `initiatePayment()`; relay the flag through backend to `PaymentGatewayPort.initiatePayment()` |
| 5 | Architecture | `port/out/` renamed to `port/external/` — deviation from IMPLEMENTATION_PLAN.md | LOW | Document the naming convention or rename to match the plan |
| 6 | Docs | RECONCILIATION.md references non-existent YAML scheduling properties | LOW | Update Configuration section to reference `@Scheduled` annotations instead |
| 7 | Docs | DEPLOYMENT.md "Running Tests" unnecessarily starts Docker services that Testcontainers handles | LOW | Remove the `docker compose up postgres redis -d` step from test instructions |

---

## Hardening Items

| # | Area | Issue | Risk | Fix |
|---|---|---|---|---|
| H1 | Database | No index on `bookings(status, hold_expires_at)` — cleanup/reconciliation queries do full table scans at scale | MEDIUM | Add `CREATE INDEX idx_bookings_status_expires ON bookings (status, hold_expires_at) WHERE status = 'PENDING';` in a V7 migration |
| H2 | Database | No index on `bookings.user_id` — any per-user listing would require a full scan | LOW | Add `CREATE INDEX idx_bookings_user_id ON bookings (user_id);` |
| H3 | Security | `webhookSecret` not validated against empty string on startup | LOW | Add `@PostConstruct` to assert `webhookSecret` is non-blank |
| H4 | Code Quality | Async thread pool (mock-payment-service) has no `threadNamePrefix` — hard to debug thread dumps | LOW | Add `ex.setThreadNamePrefix("webhook-delivery-")` in the `webhookExecutor` bean |
| H5 | Code Quality | `BookingController` injects `BookingRepositoryPort` and `SeatRepositoryPort` directly — bypasses the use-case layer for `GET /api/bookings/{id}` | LOW | Create a `GetBookingUseCase` port/service |
| H6 | API | No `@Valid` on `@RequestBody HoldSeatRequest` — null `seatId` causes NPE deep in stack | LOW | Add `@Valid` to `@RequestBody`; add `@NotNull` to `HoldSeatRequest.seatId` |
| H7 | Performance | `SeatService.getSeats()` may do 1+N DB calls on partial cache miss (individual `findById` per missing seat) | LOW | Fetch all missing seats in one batch query on partial cache miss |
| H8 | Observability | Scheduler intervals are hardcoded (`fixedDelay`) — not configurable without recompile | LOW | Externalize to `application.yml` cron expressions |

---

## Overall Assessment

```
NEEDS_FIXES
```

### Summary

The implementation is **structurally strong**: hexagonal architecture is correctly implemented, 4-layer concurrency defense is in place, HMAC/idempotency webhook handling is robust, all 18 audit event types are wired, security configuration is correct, and the test suite is excellent (true Testcontainers integration, pure unit tests, HMAC slice tests).

**Two HIGH-risk issues must be fixed before marking READY:**

1. **V6 seeds 9 seats instead of 3** — breaks the 3-seat platform concept, the frontend 3-column grid layout, and the concurrency test's seat label assertions.
2. **Webhook raw payload persistence ordering** — the raw event is not saved to `webhook_events` as the very first step. A mid-processing crash permanently loses the raw event, violating the "persist-first, process-second" invariant from the IMPLEMENTATION_PLAN.md.

**Two MEDIUM-risk issues should also be fixed:**

3. **60-second polling instead of 1-second** — contradicts README, IMPLEMENTATION_PLAN.md, and the write-through cache trade-off rationale.
4. **`simulateFail` hardcoded to `false`** — the checkbox UI has no effect; the hardcoded `localhost:9090` URL breaks when running inside Docker containers.

All other findings are LOW-risk hardening opportunities that can be addressed as follow-on work.
