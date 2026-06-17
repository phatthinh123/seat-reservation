package com.linkz.seatreservation.web.dto.response;

import java.time.Instant;

public record ErrorResponse(String error, String message, Instant timestamp) {}
