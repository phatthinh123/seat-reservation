package com.linkz.seatreservation.business.domain.model;

import com.linkz.seatreservation.business.domain.enums.SeatStatus;
import java.util.UUID;

public record Seat(UUID id, String label, SeatStatus status) {}
