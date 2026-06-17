# Skill: Concurrency & Reliability Patterns

## Layered Concurrency Strategy

### Layer 1: Redis Distributed Lock (fast-fail gate)
```java
// In web/controller/BookingController.java or business/service/BookingService.java
if (!lock.tryLock(seatId.toString(), 500, 5000)) {
    throw new SeatUnavailableException("Seat temporarily locked, please retry");
}
try {
    return holdSeatInDb(command);
} finally {
    lock.unlock(seatId.toString());
}
```
- 100 users → 1 acquires lock, 99 fail immediately (no DB hit)
- Stateless across multiple backend instances
- Lock key: `"seat-lock:" + seatId`

### Layer 2: DB Pessimistic Lock (correctness guarantee)
```java
// adapter/persistence/SeatJpaAdapter.java
@Transactional
public SeatResult holdSeatInDb(HoldSeatCommand cmd) {
    SeatEntity seat = seatRepo.findByIdForUpdate(cmd.seatId())
        .orElseThrow(SeatNotFoundException::new); // SELECT FOR UPDATE
    
    if (seat.getStatus() != SeatStatus.AVAILABLE) {
        throw new SeatUnavailableException("Seat is " + seat.getStatus());
    }
    
    // Check idempotency BEFORE modifying
    Optional<BookingEntity> existing = bookingRepo.findByIdempotencyKey(cmd.idempotencyKey());
    if (existing.isPresent()) return BookingResult.from(existing.get().toDomain());
    
    // Atomic: set HELD + create booking
    seat.setStatus(SeatStatus.HELD);
    BookingEntity booking = BookingEntity.builder()
        .userId(cmd.userId())
        .seat(seat)
        .status(BookingStatus.PENDING)
        .idempotencyKey(cmd.idempotencyKey())
        .holdExpiresAt(LocalDateTime.now().plusMinutes(10))
        .build();
    
    bookingRepo.save(booking);
    audit.log(AuditEntry.of("system", "SEAT_HELD", "seat", seat.getId()));
    audit.log(AuditEntry.of(cmd.userId(), "BOOKING_CREATED", "booking", booking.getId()));
    
    return BookingResult.from(booking.toDomain());
}
```

### Layer 3: Optimistic Lock (last resort guard)
```java
// adapter/persistence/entity/SeatEntity.java
@Version
private Long version; // JPA @Version — throws OptimisticLockException on conflict
```

## State Machine for Booking

```
PENDING ──webhook/success──► CONFIRMED  (seat → RESERVED)
PENDING ──hold expired──────► EXPIRED   (seat → AVAILABLE)
PENDING ──admin cancel──────► CANCELLED (seat → AVAILABLE)
CONFIRMED ──admin refund────► CANCELLED
EXPIRED ──────────────────── (terminal)
CANCELLED ────────────────── (terminal)
```

Enforce in webhook handler:
```java
@Transactional
public void handleWebhook(WebhookEvent event) {
    // Step 1: Persist raw payload FIRST
    webhookEventRepo.save(WebhookEventEntity.of(event));
    
    // Step 2: Idempotency check
    if (webhookEventRepo.existsByEventId(event.eventId())) {
        audit.log(AuditEntry.of("system", "WEBHOOK_DUPLICATE", "webhook", event.eventId()));
        return;
    }
    
    // Step 3: Lock booking and check state
    BookingEntity booking = bookingRepo.findByExternalPaymentIdForUpdate(event.paymentId())
        .orElseThrow();
    
    switch (booking.getStatus()) {
        case PENDING -> confirmBooking(booking, event);       // normal path
        case EXPIRED, CANCELLED -> handleLateArrival(booking, event); // auto-refund
        case CONFIRMED -> audit.log(/* WEBHOOK_DUPLICATE */); // already done
    }
}

@Transactional  // SAME transaction as handleWebhook
private void confirmBooking(BookingEntity booking, WebhookEvent event) {
    // All 4 state changes atomically:
    booking.setStatus(BookingStatus.CONFIRMED);
    booking.getSeat().setStatus(SeatStatus.RESERVED);
    paymentRepo.updateStatus(event.paymentId(), PaymentStatus.SUCCESS);
    
    audit.log(AuditEntry.of("system", "BOOKING_CONFIRMED", "booking", booking.getId()));
    audit.log(AuditEntry.of("system", "SEAT_RESERVED", "seat", booking.getSeat().getId()));
    audit.log(AuditEntry.of("system", "PAYMENT_SUCCESS", "payment", event.paymentId()));
    audit.log(AuditEntry.of("system", "WEBHOOK_PROCESSED", "webhook", event.eventId()));
}

private void handleLateArrival(BookingEntity booking, WebhookEvent event) {
    audit.log(AuditEntry.of("system", "WEBHOOK_LATE_ARRIVAL", "booking", booking.getId()));
    paymentRepo.updateStatus(event.paymentId(), PaymentStatus.REFUNDED);
    paymentGateway.refund(event.paymentId());
    audit.log(AuditEntry.of("system", "REFUND_INITIATED", "booking", booking.getId()));
}
```

## Cleanup Job — Both Tables in Same Transaction

```java
@Scheduled(fixedDelay = 60_000)
@Transactional
public void releaseExpiredHolds() {
    List<BookingEntity> expired = bookingRepo.findExpiredPendingForUpdate(LocalDateTime.now());
    for (BookingEntity b : expired) {
        b.setStatus(BookingStatus.EXPIRED);
        b.getSeat().setStatus(SeatStatus.AVAILABLE);  // BOTH in same TX
        audit.log(AuditEntry.of("system", "BOOKING_EXPIRED", "booking", b.getId()));
        audit.log(AuditEntry.of("system", "SEAT_RELEASED", "seat", b.getSeat().getId()));
    }
    // JPA dirty tracking flushes all at TX commit — atomic
    if (!expired.isEmpty()) {
        audit.log(AuditEntry.of("system", "RECONCILIATION_RUN", "system", "cleanup"));
    }
}
```

## Idempotency Key Generation

```java
// In web/controller/BookingController.java
String idempotencyKey = request.getHeader("Idempotency-Key");
if (idempotencyKey == null || idempotencyKey.isBlank()) {
    // Deterministic: SHA-256(userId + seatId) — safe for retries
    String raw = userId + ":" + seatId;
    idempotencyKey = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(raw.getBytes())
    );
}
```

## Partial Unique Index (V2 migration)

```sql
-- Allows rebooking after expiry (EXPIRED/CANCELLED excluded from index)
CREATE UNIQUE INDEX uq_one_active_booking
  ON bookings (idempotency_key)
  WHERE status IN ('PENDING', 'CONFIRMED');
```

## Concurrency Test Pattern

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
class ConcurrentBookingTest {
    @Test
    void exactly_one_winner_from_100_concurrent_requests() throws Exception {
        String seatId = "...";
        int n = 100;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        
        for (int i = 0; i < n; i++) {
            final String uid = "user-" + i;
            pool.submit(() -> {
                ready.countDown();
                start.await();
                var resp = restTemplate.postForEntity("/api/bookings",
                    new HoldSeatRequest(seatId), BookingResponse.class);
                if (resp.getStatusCode().is2xxSuccessful()) successes.incrementAndGet();
                else if (resp.getStatusCode().value() == 409) conflicts.incrementAndGet();
            });
        }
        ready.await();
        start.countDown();
        pool.awaitTermination(15, TimeUnit.SECONDS);
        
        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(99);
    }
}
```
