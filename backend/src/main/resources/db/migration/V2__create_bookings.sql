-- V2: Bookings table
-- Tracks all seat booking attempts with their lifecycle status
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

-- Partial unique index: only one ACTIVE booking per idempotency_key
-- EXPIRED/CANCELLED rows are excluded → rebooking after expiry works
-- This is a key design decision: table UNIQUE would permanently block reuse of same key
CREATE UNIQUE INDEX uq_one_active_booking
  ON bookings (idempotency_key)
  WHERE status IN ('PENDING', 'CONFIRMED');
