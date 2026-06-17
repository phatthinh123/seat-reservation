package com.linkz.seatreservation.web.controller;

import com.linkz.seatreservation.business.domain.exception.BookingNotFoundException;
import com.linkz.seatreservation.business.domain.exception.BookingNotOwnedException;
import com.linkz.seatreservation.business.domain.model.Booking;
import com.linkz.seatreservation.business.domain.model.Seat;
import com.linkz.seatreservation.business.port.in.HoldSeatUseCase;
import com.linkz.seatreservation.business.port.external.BookingRepositoryPort;
import com.linkz.seatreservation.business.port.external.SeatRepositoryPort;
import com.linkz.seatreservation.web.dto.HoldSeatRequest;
import com.linkz.seatreservation.web.dto.BookingResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@RestController
public class BookingController {
    private final HoldSeatUseCase holdSeatUseCase;
    private final BookingRepositoryPort bookingRepo;
    private final SeatRepositoryPort seatRepo;

    public BookingController(HoldSeatUseCase holdSeatUseCase,
                             BookingRepositoryPort bookingRepo,
                             SeatRepositoryPort seatRepo) {
        this.holdSeatUseCase = holdSeatUseCase;
        this.bookingRepo = bookingRepo;
        this.seatRepo = seatRepo;
    }

    @PostMapping("/api/bookings")
    public ResponseEntity<BookingResponse> createBooking(
            @RequestBody HoldSeatRequest body,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        
        String userId = jwt.getSubject();
        String idempotencyKey = request.getHeader("Idempotency-Key");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            try {
                String raw = userId + ":" + body.seatId();
                idempotencyKey = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))
                );
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }

        HoldSeatUseCase.HoldSeatCommand cmd = new HoldSeatUseCase.HoldSeatCommand(body.seatId(), userId, idempotencyKey);
        HoldSeatUseCase.BookingResult result = holdSeatUseCase.holdSeat(cmd);
        
        return ResponseEntity.status(HttpStatus.CREATED).body(
            BookingResponse.from(result.booking(), result.seatLabel())
        );
    }

    @GetMapping("/api/bookings/{id}")
    public ResponseEntity<BookingResponse> getBooking(
            @PathVariable("id") UUID bookingId,
            @AuthenticationPrincipal Jwt jwt) {
        
        String userId = jwt.getSubject();
        
        Booking booking = bookingRepo.findById(bookingId)
            .orElseThrow(() -> new BookingNotFoundException("Booking not found"));
        
        if (!booking.userId().equals(userId)) {
            throw new BookingNotOwnedException("Booking does not belong to the user");
        }
        
        String seatLabel = seatRepo.findById(booking.seatId())
            .map(Seat::label)
            .orElse("Unknown");
        
        return ResponseEntity.ok(BookingResponse.from(booking, seatLabel));
    }
}
