package com.tpthinh.seatreservation.web.dto;

public record PaymentNotificationDto(
    String eventId,
    String paymentId,
    String bookingId,
    String status
) {}
