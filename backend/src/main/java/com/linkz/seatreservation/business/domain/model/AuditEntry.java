package com.linkz.seatreservation.business.domain.model;

import java.time.LocalDateTime;
import java.util.UUID;

public record AuditEntry(
    UUID id,
    String actor,
    String action,
    String entityType,
    String entityId,
    String beforeState,
    String afterState,
    LocalDateTime createdAt
) {}
