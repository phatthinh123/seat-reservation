# Interesting Observations & Notes

This document records notable patterns, quirks, and observations found during the Milestone 5 review.

---

## 🎭 Funny / Quirky Findings

### 1. "Layer 0.5, 0.6, 0.8" Naming Convention 😄
The `BookingService.java` uses a creative decimal numbering system for concurrency layers:

```java
// 1. Idempotency Pre-Check (Layer 0.5)
// 2. Idempotency Lock (SETNX lock with 10s TTL) (Layer 0.6)
// 3. Seat Status Pre-Check (Layer 0.8)
// 4. Proceed to Distributed Lock (Layer 1)
```

The README mentions "4-Layer Defense" but the code actually has **6 layers** (0.5 → 0.6 → 0.8 → 1 → 2 → 3 → 4)! That's some floating-point layers!

### 2. The "Unknown" Seat Label
In `BookingService.java:111`:
```java
String seatLabel = "Unknown";
```
This is the fallback if a seat can't be found after a successful booking. In theory, this should never happen (the seat was just locked), but it's a defensive programming pattern that could be alarming if it ever triggers.

---

## ⚠️ Potential Issues

### 1. Tests Require Docker (Testcontainers)
The `ConcurrentBookingTest` and `SeatReservationApplicationTests` require Docker for:
- PostgreSQL 15 container
- Redis 7 container

**Impact:** Tests will skip on CI/CD environments without Docker (like some GitHub Actions runners without Docker setup).

**Solution Applied:** Added `@EnabledIf("isDockerAvailable")` to skip gracefully when Docker is unavailable.

### 2. WebMvcTest Security Configuration
`WebhookControllerTest` was failing with 403 Forbidden because `@WebMvcTest` doesn't load the full `SecurityConfig` bean.

**Solution Applied:** Added `@Import(SecurityConfig.class)` to include security configuration.

### 3. H2 vs PostgreSQL for Context Tests
The `SeatReservationApplicationTests` tries to use H2 in-memory database, but the `RedissonClient` bean still requires a Redis connection. This causes context loading failures when Redis is unavailable.

---

## 📊 Test Coverage Summary

| Test Class | Type | Status |
|------------|------|--------|
| `BookingServiceTest` | Unit (Mockito) | ✅ Passes |
| `WebhookServiceTest` | Unit (Mockito) | ✅ Passes |
| `WebhookControllerTest` | Integration (@WebMvcTest) | ✅ Passes (fixed) |
| `ConcurrentBookingTest` | Integration (Testcontainers) | ⏭️ Skips if no Docker |
| `SeatReservationApplicationTests` | Context Load | ⏭️ Skips if no Docker |

---

## 🏗️ Architecture Notes

### Hexagonal Architecture Done Right
The project follows clean hexagonal architecture with clear port/adapter separation:
- **Ports (interfaces):** `SeatRepositoryPort`, `BookingRepositoryPort`, `DistributedLockPort`, etc.
- **Adapters (implementations):** `SeatJpaAdapter`, `RedissonLockAdapter`, etc.

This makes the unit tests very clean - they only mock ports, not Spring beans.

### Write-Through Cache Pattern
The Redis cache is updated synchronously on every write operation (`cachePort.put(...)` calls throughout services). This ensures cache consistency but adds latency to write operations.

---

## 📝 Documentation Quality

The `README.md` is comprehensive and includes:
- ASCII architecture diagram
- Quick-start instructions
- 16 detailed design decisions with trade-off analysis
- Environment variable documentation

**Minor suggestion:** The 4-layer defense section could be updated to reflect the actual 6+ layers in the implementation.

---

*Last updated: Milestone 5 - Tests & Documentation*
