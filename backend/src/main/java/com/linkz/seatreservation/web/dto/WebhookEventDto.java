package com.linkz.seatreservation.web.dto;

public record WebhookEventDto(
    String eventId,
    String paymentId,
    String bookingId,
    String status
) {}
