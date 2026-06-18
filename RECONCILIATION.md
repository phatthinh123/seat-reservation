# Reconciliation Job

## What Problem Does It Solve?

In any payment flow that involves asynchronous webhooks, there is a window of uncertainty:

1. The user initiates a payment → the backend creates a `PENDING` booking.
2. The Mock Payment Service processes the payment and tries to deliver a **webhook** to confirm the result.
3. If the webhook is **delayed, lost, or never arrives** (network blip, crash, retry exhausted), the booking stays stuck in `PENDING` forever — the seat is held and nobody can book it.

The **Reconciliation Job** is the safety net for exactly this scenario. It periodically checks every stuck `PENDING` booking against the payment gateway to find out what actually happened, then drives the booking to its correct terminal state.

---

## How It Works — Step by Step

### Scheduled Reconciliation (`reconcilePendingBookings`)

Runs automatically every **5 minutes** via `@Scheduled`. The flow is:

```
1. Query DB for all bookings in PENDING state whose hold_expires_at is within 2 minutes
   ↓
2. For each booking:
   a. Lock the booking row (SELECT FOR UPDATE) to prevent concurrent updates
   b. Look up the associated payment_transaction
   c. If no payment exists, or hold has expired → mark booking EXPIRED, release seat to AVAILABLE
   d. If payment exists, call Mock Payment API: GET /payment/{externalPaymentId}
      ├─ Gateway returns SUCCESS → confirm booking (CONFIRMED), reserve seat (RESERVED), mark payment SUCCESS
      ├─ Gateway returns FAILED  → cancel booking (CANCELLED), release seat (AVAILABLE), mark payment FAILED
      └─ Gateway returns PENDING → if hold not yet expired, do nothing (check again next cycle)
   e. In all cases, write audit_log entries for every state transition
   f. Update the Redis write-through cache for the seat key (seat:cache:<id>)
```

### Manual Reconciliation (`reconcile(bookingId)`)

Triggered by an admin via the REST API:

```
POST /api/admin/reconcile/{bookingId}
Authorization: Bearer <ADMIN token>
```

Same logic as above but for a single specific booking. Useful when:
- A booking is known to be stuck and you want to resolve it immediately.
- You are investigating a specific payment dispute.

### Hold Expiry Cleanup (`releaseExpiredHolds`)

Runs every **1 minute** via a separate `@Scheduled` job. This is simpler:

```
1. Query all PENDING bookings where hold_expires_at < NOW()
2. For each: mark booking EXPIRED, release seat to AVAILABLE
   (does NOT call the payment gateway — just cleans up timed-out holds)
```

---

## State Machine Summary

```
PENDING ──[webhook SUCCESS]──────────────────────► CONFIRMED (seat: RESERVED)
PENDING ──[webhook FAILED]───────────────────────► CANCELLED (seat: AVAILABLE)
PENDING ──[reconcile, gateway=SUCCESS]───────────► CONFIRMED (seat: RESERVED)
PENDING ──[reconcile, gateway=FAILED]────────────► CANCELLED (seat: AVAILABLE)
PENDING ──[hold expired, no payment confirmed]───► EXPIRED   (seat: AVAILABLE)
```

---

## Audit Events Written

Every reconciliation action writes immutable records to `audit_logs`:

| Action | When Written |
|--------|-------------|
| `RECONCILIATION_RUN` | Every time the scheduled job starts |
| `MANUAL_RECONCILE` | When an admin triggers a specific booking reconciliation |
| `BOOKING_CONFIRMED` | Booking transitions to CONFIRMED |
| `BOOKING_CANCELLED` | Booking transitions to CANCELLED |
| `BOOKING_EXPIRED` | Booking transitions to EXPIRED (hold timed out) |
| `SEAT_RESERVED` | Seat transitions to RESERVED |
| `SEAT_RELEASED` | Seat transitions back to AVAILABLE |
| `PAYMENT_SUCCESS` | Payment confirmed by gateway |
| `PAYMENT_FAILED` | Payment failed or refunded |

---

## How to Test the Reconciliation Job

### Option A — Simulate a Delayed Webhook (Automated Test)

The `ConcurrentBookingTest.testHoldExpiry_holdTimesOutWithoutPayment_shouldReleaseSeatToAvailable` test does exactly this:

```java
// 1. Hold a seat (creates PENDING booking)
// 2. Manually expire the hold in the DB:
jdbcTemplate.update("UPDATE bookings SET hold_expires_at = ? WHERE id = ?",
    LocalDateTime.now().minusMinutes(1), bookingId);
// 3. Run the cleanup job directly:
reconciliationService.releaseExpiredHolds();
// 4. Assert seat = AVAILABLE, booking = EXPIRED
```

Run it with:
```bash
cd backend && ../gradlew test --tests "*.ConcurrentBookingTest.testHoldExpiry*"
```

---

### Option B — End-to-End via curl (Manual / Local Docker)

**Step 1: Obtain a user token**
```bash
TOKEN=$(curl -s -X POST \
  "http://localhost:8180/realms/seat-reservation/protocol/openid-connect/token" \
  -d "grant_type=password&client_id=seat-reservation-app&username=user@linkz.com&password=User1234!" \
  | jq -r .access_token)
```

**Step 2: Hold a seat**
```bash
SEAT_ID=$(curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/seats \
  | jq -r '.[0].id')

BOOKING_ID=$(curl -s -X POST http://localhost:8080/api/bookings \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"seatId\": \"$SEAT_ID\"}" \
  | jq -r .bookingId)

echo "Booking: $BOOKING_ID"
```

**Step 3: Simulate a delayed webhook (so no webhook will arrive)**
```bash
curl -s -X POST http://localhost:9090/simulate/delay
```

**Step 4: Initiate payment**
```bash
curl -s -X POST "http://localhost:8080/api/bookings/$BOOKING_ID/payment" \
  -H "Authorization: Bearer $TOKEN"
```

**Step 5: Force-expire the hold in the database**
```bash
docker exec seat-reservation-postgres psql -U seat -d seatreservation -c \
  "UPDATE bookings SET hold_expires_at = NOW() - INTERVAL '1 minute' WHERE id = '$BOOKING_ID';"
```

**Step 6: Trigger manual reconciliation as admin**
```bash
ADMIN_TOKEN=$(curl -s -X POST \
  "http://localhost:8180/realms/seat-reservation/protocol/openid-connect/token" \
  -d "grant_type=password&client_id=seat-reservation-app&username=admin@linkz.com&password=Admin1234!" \
  | jq -r .access_token)

curl -s -X POST "http://localhost:8080/api/admin/reconcile/$BOOKING_ID" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq .
```

**Step 7: Verify the state**
```bash
# Check booking status (should be EXPIRED or CANCELLED depending on gateway mock)
docker exec seat-reservation-postgres psql -U seat -d seatreservation -c \
  "SELECT status FROM bookings WHERE id = '$BOOKING_ID';"

# Check seat status (should be AVAILABLE again)
docker exec seat-reservation-postgres psql -U seat -d seatreservation -c \
  "SELECT label, status FROM seats;"

# Check audit log
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://localhost:8080/api/admin/audit-logs?limit=10" | jq .
```

---

### Option C — Test the Scheduled Job Trigger

The scheduled job runs every 5 minutes. To observe it without waiting:

1. Hold a seat and set `hold_expires_at` in the past (Step 5 above).
2. Watch the backend logs:
   ```bash
   docker compose logs -f backend | grep -i reconcil
   ```
3. Within ≤5 minutes you should see:
   ```
   Running scheduled reconciliation job
   Reconciliation PENDING but booking hold expired for <id>, releasing
   ```

---

## Configuration

Scheduled intervals are defined directly via `@Scheduled` annotations in the scheduler classes:

- **Hold Cleanup Job** ([SeatHoldCleanupJob.java](file:///home/tpthinh/playground/seat-reservation/seat-reservation/backend/src/main/java/com/linkz/seatreservation/web/scheduler/SeatHoldCleanupJob.java)): `@Scheduled(fixedDelay = 60000)` (every 1 minute)
- **Reconciliation Job** ([ReconciliationJob.java](file:///home/tpthinh/playground/seat-reservation/seat-reservation/backend/src/main/java/com/linkz/seatreservation/web/scheduler/ReconciliationJob.java)): `@Scheduled(fixedDelay = 300000)` (every 5 minutes)

To test faster locally, you can temporarily change the `fixedDelay` values (e.g., to `30000` for 30 seconds) in the Java files.
