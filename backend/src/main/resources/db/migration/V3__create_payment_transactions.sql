-- V3: Payment Transactions table
-- Records each payment attempt linked to a booking
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
