# Database Guide & Manual Interventions

This guide covers direct database connection, manual SQL queries to test edge cases (like late webhooks and reconciliation), and instructions on how to reset transaction data for fresh tests without restarting or rebuilding Docker containers.

---

## Direct Database Connection

To connect directly to the PostgreSQL database interactive shell (`psql`) running inside the Docker container:

```bash
docker exec -it seat-reservation-postgres psql -U seat -d seatreservation
```

---

## 1. Resetting Data for a Fresh Test (No Container Reloads)

If you want to clear your transaction history and start a fresh test without losing Keycloak user configurations or reloading the Docker containers, you can truncate all transactional tables and reset seat availability.

Run this single shell command from your terminal:

```bash
docker exec -it seat-reservation-postgres psql -U seat -d seatreservation -c \
  "TRUNCATE TABLE webhook_events, audit_logs, payments, bookings CASCADE; UPDATE seats SET status = 'AVAILABLE';"
```

This command:
- Clears all recorded bookings.
- Clears all payments and refunds.
- Clears all webhook transaction histories.
- Clears all append-only audit log histories.
- Resets all seat states back to `AVAILABLE` (preserving the seat configuration A1-A9).

---

## 2. Testing Reconciliation (Simulating Stuck Bookings)

To manually test how the reconciliation job rescues a booking where the payment was initiated but the webhook got lost or delayed:

### Step 1: Artificially force-expire the seat hold
Find your `bookingId` from the browser console or by querying the database:
```sql
SELECT id, status, hold_expires_at FROM bookings WHERE status = 'PENDING';
```

Modify the database to put the hold expiration in the past:
```sql
UPDATE bookings SET hold_expires_at = NOW() - INTERVAL '1 minute' WHERE id = 'YOUR_BOOKING_ID';
```

### Step 2: Trigger Reconciliation
Either wait for the scheduled job to run (every 5 minutes) or trigger it manually as an administrator:
* **UI**: Go to the Admin Dashboard (login as `admin@linkz.com`) and click **Reconcile** next to the pending booking.
* **API**: Submit a POST request:
  ```bash
  curl -X POST http://localhost:8080/api/admin/reconcile/YOUR_BOOKING_ID \
    -H "Authorization: Bearer ADMIN_ACCESS_TOKEN"
  ```

### Step 3: Verify State Transition
Run these queries to verify the seat has been released and the booking is marked `EXPIRED`:
```sql
SELECT id, status, hold_expires_at FROM bookings WHERE id = 'YOUR_BOOKING_ID';
SELECT label, status FROM seats WHERE status = 'AVAILABLE';
```

---

## 3. Testing Auto-Refund (Simulating Late Webhooks)

To test the system's ability to trigger an automatic refund if a successful payment webhook arrives *after* the hold window has expired:

### Step 1: Force-expire the seat hold in the database
Find the `bookingId` of the pending seat hold and set it in the past:
```sql
UPDATE bookings SET hold_expires_at = NOW() - INTERVAL '1 minute' WHERE id = 'YOUR_BOOKING_ID';
```

### Step 2: Trigger Webhook Delivery
Complete the payment flow (or simulate webhook arrival via curl or payment mock gateway).
The backend will immediately detect the expired status, proceed with `REFUND_INITIATED`, trigger the gateway refund, record `REFUND_COMPLETED`, and transition the payment status to `REFUNDED` while keeping the seat `AVAILABLE`.

---

## 4. Querying Audit Logs & Webhook Events

Use these commands to inspect what is happening under the hood:

### View last 10 audit logs with payload context:
```sql
SELECT created_at, actor, action, entity_type, entity_id, after_state 
FROM audit_logs 
ORDER BY created_at DESC 
LIMIT 10;
```

### View all webhook events and their processing statuses:
```sql
SELECT event_id, status, processed_at, error 
FROM webhook_events 
ORDER BY processed_at DESC;
```
