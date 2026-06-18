# Skill: Hexagonal Architecture Rules

## Package Layout (strict)

```
com.tpthinh.seatreservation/
├── business/        ← CORE — zero framework dependencies
│   ├── domain/      ← models, enums, exceptions (plain Java records/classes)
│   ├── port/in/     ← use case interfaces (what web calls)
│   ├── port/out/    ← dependency interfaces (what business needs from outside)
│   └── service/     ← implementations of port/in (inject port/out via constructor)
├── web/             ← PORT IN — inbound adapters
│   ├── controller/  ← @RestController — calls business.port.in
│   ├── scheduler/   ← @Scheduled — calls business.port.in or service directly
│   └── dto/         ← request/response DTOs (NOT domain models)
└── adapter/         ← PORT OUT — outbound adapters
    ├── persistence/ ← implements business.port.out via Spring Data JPA
    │   └── entity/  ← @Entity JPA classes (separate from domain models)
    ├── lock/        ← implements DistributedLockPort via Redisson
    ├── cache/       ← implements CachePort via Spring Cache + Redis
    └── payment/     ← implements PaymentGatewayPort via RestTemplate/WebClient
```

## Dependency Rule (MUST enforce)

```
web  →  business  ←  adapter
         ↑
   (no outward deps)
```

✅ `web.controller` imports `business.port.in.HoldSeatUseCase`
✅ `adapter.persistence` imports `business.port.out.BookingRepositoryPort`
❌ `business.service` MUST NOT import anything from `web` or `adapter`
❌ `business.domain` MUST NOT have @Entity, @Component, @Service annotations
❌ `web.controller` MUST NOT import `adapter.*`

## Domain Models vs JPA Entities

Domain models are pure Java:
```java
// business/domain/model/Booking.java
public record Booking(
    UUID id,
    String userId,
    UUID seatId,
    BookingStatus status,
    String idempotencyKey,
    LocalDateTime holdExpiresAt,
    LocalDateTime createdAt
) {}
```

JPA entities are infrastructure (in adapter/persistence/entity/):
```java
// adapter/persistence/entity/BookingEntity.java
@Entity
@Table(name = "bookings")
public class BookingEntity {
    @Id UUID id;
    String userId;
    UUID seatId;
    @Enumerated(EnumType.STRING) BookingStatus status;
    // ...
    
    public Booking toDomain() { return new Booking(...); }
    public static BookingEntity fromDomain(Booking b) { ... }
}
```

## Port Interfaces

Inbound ports (use cases):
```java
// business/port/in/HoldSeatUseCase.java
public interface HoldSeatUseCase {
    BookingResult holdSeat(HoldSeatCommand command);
}
```

Outbound ports:
```java
// business/port/out/BookingRepositoryPort.java
public interface BookingRepositoryPort {
    Booking save(Booking booking);
    Optional<Booking> findByIdForUpdate(UUID id);  // SELECT FOR UPDATE
    List<Booking> findExpiredPending(LocalDateTime before);
    Optional<Booking> findByIdempotencyKey(String key);
}
```

## Service Implementation

Services only depend on ports (constructor injection):
```java
// business/service/BookingService.java
@Service
public class BookingService implements HoldSeatUseCase {
    private final SeatRepositoryPort seatRepo;
    private final BookingRepositoryPort bookingRepo;
    private final DistributedLockPort lock;
    private final AuditPort audit;
    
    // NO JPA, NO Redis, NO Spring annotations other than @Service and @Transactional
    
    @Override
    @Transactional
    public BookingResult holdSeat(HoldSeatCommand cmd) { ... }
}
```

## Test Strategy

Because business has zero framework deps:
```java
// src/test/business/BookingServiceTest.java
class BookingServiceTest {
    // No @SpringBootTest needed!
    BookingService service = new BookingService(
        mock(SeatRepositoryPort.class),
        mock(BookingRepositoryPort.class),
        mock(DistributedLockPort.class),
        mock(AuditPort.class)
    );
}
```
