package com.linkz.seatreservation.web.config;

import com.linkz.seatreservation.business.domain.exception.*;
import com.linkz.seatreservation.web.dto.response.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SeatUnavailableException.class)
    public ResponseEntity<ErrorResponse> onSeatUnavailable(SeatUnavailableException e) {
        return ResponseEntity.status(409)
            .body(new ErrorResponse("SEAT_UNAVAILABLE", e.getMessage(), Instant.now()));
    }

    @ExceptionHandler(BookingNotFoundException.class)
    public ResponseEntity<ErrorResponse> onBookingNotFound(BookingNotFoundException e) {
        return ResponseEntity.status(404)
            .body(new ErrorResponse("BOOKING_NOT_FOUND", e.getMessage(), Instant.now()));
    }

    @ExceptionHandler(BookingNotOwnedException.class)
    public ResponseEntity<ErrorResponse> onBookingNotOwned(BookingNotOwnedException e) {
        return ResponseEntity.status(403)
            .body(new ErrorResponse("BOOKING_NOT_OWNED", e.getMessage(), Instant.now()));
    }

    @ExceptionHandler(BookingExpiredException.class)
    public ResponseEntity<ErrorResponse> onBookingExpired(BookingExpiredException e) {
        return ResponseEntity.status(409)
            .body(new ErrorResponse("BOOKING_EXPIRED", e.getMessage(), Instant.now()));
    }

    @ExceptionHandler(BookingAlreadyPaidException.class)
    public ResponseEntity<ErrorResponse> onBookingAlreadyPaid(BookingAlreadyPaidException e) {
        return ResponseEntity.status(409)
            .body(new ErrorResponse("BOOKING_ALREADY_PAID", e.getMessage(), Instant.now()));
    }

    @ExceptionHandler(InvalidWebhookSignatureException.class)
    public ResponseEntity<ErrorResponse> onInvalidWebhookSignature(InvalidWebhookSignatureException e) {
        return ResponseEntity.status(400)
            .body(new ErrorResponse("INVALID_WEBHOOK_SIGNATURE", e.getMessage(), Instant.now()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> onUnauthorized(UnauthorizedException e) {
        return ResponseEntity.status(401)
            .body(new ErrorResponse("UNAUTHORIZED", e.getMessage(), Instant.now()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> onAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(403)
            .body(new ErrorResponse("FORBIDDEN", e.getMessage(), Instant.now()));
    }

    @ExceptionHandler(DuplicateRequestException.class)
    public ResponseEntity<ErrorResponse> onDuplicateRequest(DuplicateRequestException e) {
        return ResponseEntity.status(409)
            .body(new ErrorResponse("DUPLICATE_REQUEST", e.getMessage(), Instant.now()));
    }
}
