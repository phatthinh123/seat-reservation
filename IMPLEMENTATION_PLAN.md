# Implementation Plan - Decoupling Audit System from Business Services

## Goal
The audit logging logic (`auditPort.log(...)`) is currently scattered across several core services, mixing transaction details, before/after states, and logging metadata with core business rules. This plan details how to decouple auditing by refactoring it to use **Spring Application Events** (raising events in the services, and consuming/logging them in a listener in the adapter layer).

## Design Options

### Option A: Semantic Domain Events (Highly Decoupled)
We define specific event classes representing business actions (e.g., `SeatHeldEvent`, `BookingCreatedEvent`, `BookingConfirmedEvent`, etc.) in the domain layer.
* **Service Layer**: Injects Spring's `ApplicationEventPublisher` and publishes semantic events:
  ```java
  eventPublisher.publishEvent(new SeatHeldEvent(seatId, beforeState, afterState));
  ```
* **Adapter Layer**: An `AuditEventListener` listens to all these events and maps them to `AuditPort.log(...)` calls.
* **Pros**: Cleanest separation of concerns; no audit terms leak into domain code; easy to add new side effects later.
* **Cons**: Introduces multiple new class files (around 10–12 event records) and mapping code in the listener.

### Option B: Unified `AuditEvent` Publisher (Simple & Pragmatic - Recommended)
We define a single `AuditEvent` record in the domain/event package.
* **Service Layer**: Injects Spring's `ApplicationEventPublisher` and publishes a unified `AuditEvent`:
  ```java
  eventPublisher.publishEvent(new AuditEvent("system", "SEAT_HELD", "SEAT", id, before, after));
  ```
* **Adapter Layer**: A generic `AuditEventListener` listens for `AuditEvent` and logs it directly via the `AuditPort`:
  ```java
  @Component
  public class AuditEventListener {
      private final AuditPort auditPort;

      @Async // optional
      @EventListener
      public void onAuditEvent(AuditEvent event) {
          auditPort.log(event.actor(), event.action(), event.entityType(), event.entityId(), event.beforeState(), event.afterState());
      }
  }
  ```
* **Pros**: 
  * Simple and extremely low boilerplate (only 1 new event record class, 1 listener class).
  * Immediately removes `AuditPort` dependency from all services.
  * Easy to refactor.
* **Cons**: Services still define the structural layout of the audit entry.

---

## Proposed Changes

We will implement **Option B** (Unified `AuditEvent` Publisher) unless you prefer Option A, as it provides the cleanest balance between decoupling and code size.

### Auditing Refactoring

#### [NEW] [AuditEvent.java](file:///wsl.localhost/Ubuntu/home/tpthinh/playground/seat-reservation/seat-reservation/backend/src/main/java/com/linkz/seatreservation/business/domain/event/AuditEvent.java)
Create the record class holding audit fields:
```java
package com.linkz.seatreservation.business.domain.event;

public record AuditEvent(
    String actor,
    String action,
    String entityType,
    String entityId,
    Object beforeState,
    Object afterState
) {}
```

#### [NEW] [AuditEventListener.java](file:///wsl.localhost/Ubuntu/home/tpthinh/playground/seat-reservation/seat-reservation/backend/src/main/java/com/linkz/seatreservation/adapter/audit/AuditEventListener.java)
Create the adapter listener class:
```java
package com.linkz.seatreservation.adapter.audit;

import com.linkz.seatreservation.business.domain.event.AuditEvent;
import com.linkz.seatreservation.business.port.external.AuditPort;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AuditEventListener {
    private final AuditPort auditPort;

    public AuditEventListener(AuditPort auditPort) {
        this.auditPort = auditPort;
    }

    @EventListener
    public void handleAuditEvent(AuditEvent event) {
        auditPort.log(
            event.actor(),
            event.action(),
            event.entityType(),
            event.entityId(),
            event.beforeState(),
            event.afterState()
        );
    }
}
```

#### [MODIFY] [BookingService.java](file:///wsl.localhost/Ubuntu/home/tpthinh/playground/seat-reservation/seat-reservation/backend/src/main/java/com/linkz/seatreservation/business/service/BookingService.java)
* Remove `AuditPort` injection.
* Inject `ApplicationEventPublisher`.
* Replace `auditPort.log(...)` with `eventPublisher.publishEvent(new AuditEvent(...))`.

#### [MODIFY] [PaymentNotificationService.java](file:///wsl.localhost/Ubuntu/home/tpthinh/playground/seat-reservation/seat-reservation/backend/src/main/java/com/linkz/seatreservation/business/service/PaymentNotificationService.java)
* Remove `AuditPort` injection.
* Inject `ApplicationEventPublisher`.
* Replace `auditPort.log(...)` with `eventPublisher.publishEvent(new AuditEvent(...))`.

#### [MODIFY] [PaymentService.java](file:///wsl.localhost/Ubuntu/home/tpthinh/playground/seat-reservation/seat-reservation/backend/src/main/java/com/linkz/seatreservation/business/service/PaymentService.java)
* Remove `AuditPort` injection.
* Inject `ApplicationEventPublisher`.
* Replace `auditPort.log(...)` with `eventPublisher.publishEvent(new AuditEvent(...))`.

#### [MODIFY] [ReconciliationService.java](file:///wsl.localhost/Ubuntu/home/tpthinh/playground/seat-reservation/seat-reservation/backend/src/main/java/com/linkz/seatreservation/business/service/ReconciliationService.java)
* Remove `AuditPort` injection.
* Inject `ApplicationEventPublisher`.
* Replace `auditPort.log(...)` with `eventPublisher.publishEvent(new AuditEvent(...))`.

---

## Verification Plan

### Automated Tests
* Run `./gradlew test` to ensure compile safety, verification logic, and unit/integration/concurrency tests all pass.
* Verify mocks in `BookingServiceTest`, `PaymentNotificationServiceTest`, and `WebhookServiceTest` to ensure they stub and verify `ApplicationEventPublisher` instead of `AuditPort`.
