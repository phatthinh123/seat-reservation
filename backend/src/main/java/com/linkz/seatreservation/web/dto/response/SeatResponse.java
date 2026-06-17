package com.linkz.seatreservation.web.dto.response;

import com.linkz.seatreservation.business.domain.enums.SeatStatus;
import java.util.UUID;

public record SeatResponse(UUID id, String label, SeatStatus status) {}
