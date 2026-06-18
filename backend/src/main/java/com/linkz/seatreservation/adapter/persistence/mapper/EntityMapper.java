package com.linkz.seatreservation.adapter.persistence.mapper;

import com.linkz.seatreservation.adapter.persistence.entity.*;
import com.linkz.seatreservation.business.domain.model.*;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface EntityMapper {

    @Mapping(target = "heldBy", ignore = true)
    @Mapping(target = "bookingId", ignore = true)
    @Mapping(target = "idempotencyKey", ignore = true)
    Seat toDomain(SeatEntity entity);

    SeatEntity fromDomain(Seat seat);

    Booking toDomain(BookingEntity entity);
    BookingEntity fromDomain(Booking booking);

    Payment toDomain(PaymentTransactionEntity entity);
    PaymentTransactionEntity fromDomain(Payment payment);

    AuditEntry toDomain(AuditLogEntity entity);
    AuditLogEntity fromDomain(AuditEntry entry);

    PaymentNotification toDomain(PaymentNotificationEntity entity);
    PaymentNotificationEntity fromDomain(PaymentNotification notification);
}
