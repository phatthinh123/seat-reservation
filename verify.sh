#!/usr/bin/env bash
set -e

# Load environment variables from .env if present
if [ -f .env ]; then
  # Export variables to subshells, ignoring comments
  export $(grep -v '^#' .env | xargs)
fi


echo "=== 1. Obtaining Token ==="
TOKEN=$(curl -s -X POST http://localhost:8180/realms/seat-reservation/protocol/openid-connect/token \
  -d "client_id=seat-reservation-app&username=user@tpthinh.com&password=User1234!&grant_type=password" \
  | python3 -c "import sys, json; print(json.load(sys.stdin)['access_token'])")

if [ -z "$TOKEN" ] || [ "$TOKEN" == "null" ]; then
  echo "Failed to obtain access token."
  exit 1
fi
echo "Successfully obtained access token."

echo "=== 2. Listing Seats ==="
SEATS=$(curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/seats)
echo "Seats: $SEATS"

# Get seat A2 UUID (A1 is reserved from last run, let's use A2 to make the test clean)
A2_UUID=$(echo "$SEATS" | python3 -c "import sys, json; print(next(s['id'] for s in json.load(sys.stdin) if s['label']=='A2'))")
echo "A2 Seat UUID: $A2_UUID"

echo "=== 3. Holding Seat A2 ==="
BOOKING=$(curl -s -X POST http://localhost:8080/api/bookings \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"seatId\":\"$A2_UUID\"}")
echo "Booking Response: $BOOKING"

BOOKING_ID=$(echo "$BOOKING" | python3 -c "import sys, json; print(json.load(sys.stdin)['bookingId'])")
IDEM_KEY=$(echo "$BOOKING" | python3 -c "import sys, json; print(json.load(sys.stdin)['idempotencyKey'])")
echo "Booking ID: $BOOKING_ID"
echo "Idempotency Key: $IDEM_KEY"

echo "=== 4. Test Idempotency: holding same seat A2 again with same Idempotency Key ==="
BOOKING_RETRY=$(curl -s -X POST http://localhost:8080/api/bookings \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $IDEM_KEY" \
  -H "Content-Type: application/json" \
  -d "{\"seatId\":\"$A2_UUID\"}")
echo "Booking Retry Response: $BOOKING_RETRY"
RETRY_ID=$(echo "$BOOKING_RETRY" | python3 -c "import sys, json; print(json.load(sys.stdin)['bookingId'])")
if [ "$BOOKING_ID" == "$RETRY_ID" ]; then
  echo "Idempotency check: PASS (Same booking ID returned)"
else
  echo "Idempotency check: FAIL"
  exit 1
fi

echo "=== 5. Initiate Payment ==="
PAYMENT=$(curl -s -X POST http://localhost:8080/api/bookings/$BOOKING_ID/payment \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json")
echo "Payment Response: $PAYMENT"
PAYMENT_ID=$(echo "$PAYMENT" | python3 -c "import sys, json; print(json.load(sys.stdin)['paymentId'])")
echo "Payment ID: $PAYMENT_ID"

echo "=== 6. Wait for Webhook Delivery (3s) ==="
sleep 3

echo "=== 7. Verify Seat Status is RESERVED ==="
SEATS_AFTER=$(curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/seats)
echo "Seats After: $SEATS_AFTER"
A2_STATUS=$(echo "$SEATS_AFTER" | python3 -c "import sys, json; print(next(s['status'] for s in json.load(sys.stdin) if s['label']=='A2'))")
if [ "$A2_STATUS" == "RESERVED" ]; then
  echo "Seat status check: PASS (RESERVED)"
else
  echo "Seat status check: FAIL (Status is $A2_STATUS)"
  exit 1
fi

echo "=== 8. Test Duplicate Webhook ==="
# Construct duplicate webhook payload
EVENT_ID_DUP="dup-event-$(date +%s)"
BODY="{\"eventId\":\"$EVENT_ID_DUP\",\"paymentId\":\"$PAYMENT_ID\",\"bookingId\":\"$BOOKING_ID\",\"status\":\"SUCCESS\"}"
SIGNATURE=$(python3 -c "import hmac, hashlib, sys; print(hmac.new(sys.argv[2].encode('utf-8'), sys.argv[1].encode('utf-8'), hashlib.sha256).hexdigest())" "$BODY" "${WEBHOOK_SECRET:-change-me-in-production}")

RESPONSE_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8080/api/webhooks/payment \
  -H "Content-Type: application/json" \
  -H "X-Signature: $SIGNATURE" \
  -d "$BODY")

if [ "$RESPONSE_CODE" == "200" ]; then
  echo "Duplicate webhook check: PASS (Returns 200)"
else
  echo "Duplicate webhook check: FAIL (Returns $RESPONSE_CODE)"
  exit 1
fi

echo "=== 9. Test Late Arrival Webhook ==="
# 1. Hold seat A3
A3_UUID=$(echo "$SEATS" | python3 -c "import sys, json; print(next(s['id'] for s in json.load(sys.stdin) if s['label']=='A3'))")
BOOKING_LATE=$(curl -s -X POST http://localhost:8080/api/bookings \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"seatId\":\"$A3_UUID\"}")
BOOKING_LATE_ID=$(echo "$BOOKING_LATE" | python3 -c "import sys, json; print(json.load(sys.stdin)['bookingId'])")
echo "Late booking created: $BOOKING_LATE_ID"

# 2. Initiate Payment for Late Booking
PAYMENT_LATE=$(curl -s -X POST http://localhost:8080/api/bookings/$BOOKING_LATE_ID/payment \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json")
PAYMENT_LATE_ID=$(echo "$PAYMENT_LATE" | python3 -c "import sys, json; print(json.load(sys.stdin)['paymentId'])")
echo "Late Payment ID: $PAYMENT_LATE_ID"

# 3. Simulate Hold Expiry in DB
docker exec -i seat-reservation-postgres psql -U seat -d seatreservation -c "UPDATE bookings SET status = 'EXPIRED' WHERE id = '$BOOKING_LATE_ID';"
docker exec -i seat-reservation-postgres psql -U seat -d seatreservation -c "UPDATE seats SET status = 'AVAILABLE' WHERE id = '$A3_UUID';"

# 4. Trigger Webhook SUCCESS for the expired booking
EVENT_ID_LATE="late-event-$(date +%s)"
BODY_LATE="{\"eventId\":\"$EVENT_ID_LATE\",\"paymentId\":\"$PAYMENT_LATE_ID\",\"bookingId\":\"$BOOKING_LATE_ID\",\"status\":\"SUCCESS\"}"
SIGNATURE_LATE=$(python3 -c "import hmac, hashlib, sys; print(hmac.new(sys.argv[2].encode('utf-8'), sys.argv[1].encode('utf-8'), hashlib.sha256).hexdigest())" "$BODY_LATE" "${WEBHOOK_SECRET:-change-me-in-production}")

RESPONSE_CODE_LATE=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8080/api/webhooks/payment \
  -H "Content-Type: application/json" \
  -H "X-Signature: $SIGNATURE_LATE" \
  -d "$BODY_LATE")

if [ "$RESPONSE_CODE_LATE" == "200" ]; then
  echo "Late arrival webhook HTTP check: PASS (Returns 200)"
else
  echo "Late arrival webhook HTTP check: FAIL (Returns $RESPONSE_CODE_LATE)"
  exit 1
fi

# Verify payment transaction is updated to REFUNDED in DB
PAYMENT_STATUS_DB=$(docker exec -i seat-reservation-postgres psql -U seat -d seatreservation -t -A -c "SELECT status FROM payment_transactions WHERE external_payment_id = '$PAYMENT_LATE_ID';")
if [ "$PAYMENT_STATUS_DB" == "REFUNDED" ]; then
  echo "Late arrival refund status check: PASS (REFUNDED)"
else
  echo "Late arrival refund status check: FAIL ($PAYMENT_STATUS_DB)"
  exit 1
fi

echo "=== 10. Verify Audit Logs ==="
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8180/realms/seat-reservation/protocol/openid-connect/token \
  -d "client_id=seat-reservation-app&username=admin@tpthinh.com&password=Admin1234!&grant_type=password" \
  | python3 -c "import sys, json; print(json.load(sys.stdin)['access_token'])")

AUDIT_LOGS=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "http://localhost:8080/api/admin/audit-logs?limit=40")
echo "Audit Logs Count: $(echo "$AUDIT_LOGS" | python3 -c "import sys, json; print(len(json.load(sys.stdin)))")"
echo "Events recorded: $(echo "$AUDIT_LOGS" | python3 -c "import sys, json; print([l['action'] for l in json.load(sys.stdin)])")"

echo "=== All checks completed! ==="
