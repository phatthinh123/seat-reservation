-- V4: Payment Notifications (idempotency store)
-- Stores raw payment notifications for exactly-once processing
-- event_id UNIQUE ensures we never process the same notification twice
CREATE TABLE payment_notifications (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  provider     VARCHAR(50) NOT NULL,
  event_id     VARCHAR(255) NOT NULL UNIQUE,
  raw_payload  TEXT NOT NULL,
  status       VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
  processed_at TIMESTAMP,
  error        TEXT,
  created_at   TIMESTAMP DEFAULT NOW()
);
