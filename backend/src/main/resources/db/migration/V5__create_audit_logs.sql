-- V5: Audit Logs (append-only — NEVER UPDATE or DELETE)
-- Immutable event log for compliance and debugging
-- before_state and after_state stored as JSONB for full context
CREATE TABLE audit_logs (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  actor        VARCHAR(255) NOT NULL,      -- userId or 'system'
  action       VARCHAR(100) NOT NULL,      -- e.g. SEAT_HELD, BOOKING_CONFIRMED, etc.
  entity_type  VARCHAR(50) NOT NULL,       -- e.g. 'SEAT', 'BOOKING', 'PAYMENT'
  entity_id    VARCHAR(255) NOT NULL,      -- UUID of the entity
  before_state JSONB,                      -- state before the action
  after_state  JSONB,                      -- state after the action
  created_at   TIMESTAMP DEFAULT NOW()
);
