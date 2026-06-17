# Agent: Reviewer — Rubber Duck & Hardening

## Your Role
You are the reviewer agent. All 5 milestones are complete.
Your job is to:
1. Rubber-duck every significant implementation decision
2. Verify the code matches the IMPLEMENTATION_PLAN.md
3. Find gaps, deviations, or hardening opportunities
4. Produce a structured review report

## What to Check

### Architecture Integrity
- [ ] No `import` from `web.*` or `adapter.*` inside `business.*` packages
- [ ] No `@Entity`, `@Component`, `@Repository` in `business/domain/`
- [ ] All port interfaces are in `business/port/in/` or `business/port/out/`
- [ ] All `@RestController` classes are in `web/controller/`
- [ ] All JPA `@Entity` classes are in `adapter/persistence/entity/`
- [ ] `BookingService` constructor only takes port interfaces — no Spring Data repos directly

### Concurrency
- [ ] `BookingService.holdSeat()` acquires Redis lock BEFORE calling `seatRepo.findByIdForUpdate`
- [ ] `findByIdForUpdate` uses `@Lock(PESSIMISTIC_WRITE)`
- [ ] Cleanup job updates BOTH `booking.status` AND `seat.status` in same `@Transactional`
- [ ] Redis lock is released in `finally` block
- [ ] Idempotency key is checked INSIDE the `@Transactional` (not before)

### Webhook Reliability
- [ ] Raw payload persisted to `webhook_events` BEFORE any business logic
- [ ] `webhook_events.event_id` has UNIQUE constraint (verify in V4 migration)
- [ ] Webhook handler uses `SELECT FOR UPDATE` on booking
- [ ] State machine covers ALL 4 cases: PENDING, CONFIRMED, EXPIRED, CANCELLED
- [ ] `confirmBooking()` updates booking + seat + payment_transaction in ONE transaction
- [ ] Late arrival auto-refund calls `PaymentGatewayPort.refund()`

### Audit Log
- [ ] Count all 18 audit event types in the codebase
  - SEAT_HELD, SEAT_RELEASED, SEAT_RESERVED
  - BOOKING_CREATED, BOOKING_CONFIRMED, BOOKING_EXPIRED, BOOKING_CANCELLED
  - PAYMENT_INITIATED, PAYMENT_SUCCESS, PAYMENT_FAILED
  - REFUND_INITIATED, REFUND_COMPLETED
  - WEBHOOK_RECEIVED, WEBHOOK_PROCESSED, WEBHOOK_DUPLICATE, WEBHOOK_LATE_ARRIVAL
  - RECONCILIATION_RUN, MANUAL_RECONCILE
- [ ] `AuditLogJpaRepository` has NO `update()` or `delete()` methods
- [ ] Audit calls are inside the same `@Transactional` as the business operation

### Security
- [ ] Webhook endpoint (`POST /api/webhooks/payment`) is `permitAll()` in SecurityConfig
- [ ] HMAC verification rejects on mismatch (returns 400, not 401 or 500)
- [ ] `MessageDigest.isEqual()` used for constant-time comparison (not `String.equals()`)
- [ ] Admin endpoints have `hasRole("ADMIN")` check
- [ ] JWT `sub` claim used as `userId` (not `preferred_username`)

### Database
- [ ] Partial unique index present in V2 migration (not a table constraint)
- [ ] `hold_expires_at` is NOT NULL (confirmed intentional — see trade-offs)
- [ ] `webhook_events.event_id` has UNIQUE constraint
- [ ] `audit_logs` has no FK on actor (allows "system" as string)
- [ ] V6 seeds exactly 3 seats: A1, A2, A3

### Cache
- [ ] Redis keys are individual `seat:cache:<id>` (not a single key for all seats)
- [ ] SeatService uses `MGET` to lazy load from Redis cache and database on cache miss
- [ ] Cache is write-through; any status update (held, reserved, released, manual reconcile, etc.) updates the individual seat cache key immediately
- [ ] Redis seat cache has a safety TTL (e.g. 24 hours), not 2 seconds

### Frontend
- [ ] Polling uses `setInterval` with cleanup on component destroy (`ngOnDestroy`)
- [ ] Hold countdown timer uses `holdExpiresAt` from booking response
- [ ] `simulateFail` checkbox value is sent to backend in payment request
- [ ] Admin route has `CanActivate` guard that checks ADMIN role
- [ ] Auth interceptor attaches token to ALL `/api/*` requests (not just some)

### Tests
- [ ] `ConcurrentBookingTest` uses `CountDownLatch` for true simultaneous start
- [ ] `BookingServiceTest` has NO Spring annotations (pure unit test)
- [ ] `WebhookControllerTest` tests constant-time signature comparison (invalid sig → 400)
- [ ] Test profile uses `application-test.yml` with Testcontainers-provided connection strings

### README
- [ ] `docker compose up --build -d` is the only command needed to run
- [ ] All 6 ports are documented
- [ ] Both user credentials are listed
- [ ] Trade-offs section covers at least: Keycloak, HMAC vs mTLS, Redis+DB lock, hexagonal
- [ ] Known limitations are honest (e.g. "mock payment service is in-memory, data lost on restart")

## Hardening Checklist

For each gap found, document:
1. **What's missing/wrong**
2. **Risk level** (HIGH/MEDIUM/LOW)
3. **Fix** (code change or documentation)

Common hardening areas to check:
- Missing `@Transactional(readOnly = true)` on read-only methods
- Missing `@Valid` on request body parameters
- N+1 query risk in admin endpoint (use JOIN FETCH or DTO projection)
- Missing index on `bookings.status` + `bookings.hold_expires_at` (for cleanup job query)
- Missing `@Indexed` on `bookings.user_id` (for per-user queries)
- Webhook secret not validated against empty string (would allow bypass)
- Thread pool executor not named (hard to debug in production logs)

## Output Format

Produce a structured `REVIEW_REPORT.md` at project root:

```markdown
# Review Report

## Architecture: PASS/FAIL
- [x] No cross-layer imports
- [ ] Issue found: ...

## Concurrency: PASS/FAIL
...

## Gaps Found
| # | Area | Issue | Risk | Fix |
|---|---|---|---|---|
| 1 | Cache | Missing @CacheEvict on cleanup job | HIGH | Add annotation |

## Hardening Items
...

## Overall Assessment
READY / NEEDS_FIXES
```
