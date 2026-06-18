package com.linkz.seatreservation.business.domain.model;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentNotification(
    UUID id,
    String provider,
    String eventId,
    String rawPayload,
    String status,
    LocalDateTime processedAt,
    String error,
    LocalDateTime createdAt
) {}
