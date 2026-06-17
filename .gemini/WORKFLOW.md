# Agentic Build Workflow

## Overview

This project is built by a pipeline of autonomous agents. Each agent owns a milestone,
implements it, verifies it, then the next agent starts. All design decisions live in
`IMPLEMENTATION_PLAN.md` — agents read it as their source of truth.

## Milestone Pipeline

```
[Milestone 1: Foundation]
  → Gradle multi-project scaffold
  → Docker Compose (postgres, redis, keycloak, mock-payment-service, backend, frontend)
  → Flyway migrations V1-V6
  → Verify: docker compose up --build runs without errors

[Milestone 2: Auth]
  → Keycloak realm export + Docker volume mount
  → Spring Security JWT Resource Server config
  → Angular Keycloak JS adapter + auth interceptor + guards
  → Verify: login → JWT → protected /api/seats returns 200

[Milestone 3: Business Logic]
  → Full hexagonal package structure (business / web / adapter)
  → Seat API with Redis cache (2s TTL)
  → Booking API with Redis distributed lock + SELECT FOR UPDATE
  → SeatHoldCleanupJob (@Scheduled 1 min)
  → Mock payment service (async webhook + retry + refund)
  → WebhookService (HMAC, idempotency, state machine)
  → ReconciliationJob (@Scheduled 5 min)
  → AuditService (all 18 event types)
  → Verify: full booking flow via curl (hold → pay → webhook → RESERVED)

[Milestone 4: Frontend]
  → 5 Angular pages: login, seats, payment, confirmation, admin
  → JWT interceptor attaches Bearer token
  → 1s seat polling (backed by Redis cache)
  → "Simulate failure" checkbox on payment page
  → Admin page: pending bookings table + Reconcile button + audit log
  → Verify: full UI flow works end-to-end in browser

[Milestone 5: Tests + Documentation]
  → ConcurrentBookingTest: 100 threads, Testcontainers (real PG + Redis)
  → BookingServiceTest: pure unit test, no Spring context
  → WebhookServiceTest: pure unit test, no Spring context
  → WebhookControllerTest: HMAC signature verification
  → README.md: setup, architecture, trade-offs, ports, credentials
  → .env.example: all environment variables documented
  → Verify: ./gradlew test passes

[Review: Rubber Duck]
  → Walk through every file changed in each milestone
  → Check IMPLEMENTATION_PLAN.md decisions are correctly reflected in code
  → Verify all 18 audit events are wired
  → Verify state machine transitions are exhaustive
  → Verify no direct cross-layer imports (business never imports web/adapter)
  → Report any gaps or deviations from plan
```

## Agent Prompts

Each milestone has a corresponding agent prompt in `.gemini/agents/`:
- `milestone-1-foundation.md`
- `milestone-2-auth.md`
- `milestone-3-business.md`
- `milestone-4-frontend.md`
- `milestone-5-testing.md`
- `reviewer.md`

## Shared Skills

Reusable knowledge referenced by all agents:
- `.gemini/skills/hexagonal-architecture.md` — package rules, dependency direction
- `.gemini/skills/spring-boot-patterns.md` — locking, scheduling, cache patterns
- `.gemini/skills/concurrency-patterns.md` — Redis lock + DB lock + state machine

## Project Path

All code lives in: `seat-reservation/seat-reservation/`
Implementation plan: `seat-reservation/seat-reservation/IMPLEMENTATION_PLAN.md`
