-- V1: Seats table
-- Three seats to reserve, with optimistic locking version
CREATE TABLE seats (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  label      VARCHAR(10) NOT NULL UNIQUE,
  status     VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',  -- AVAILABLE | HELD | RESERVED
  version    BIGINT NOT NULL DEFAULT 0,                 -- JPA @Version
  updated_at TIMESTAMP DEFAULT NOW()
);
