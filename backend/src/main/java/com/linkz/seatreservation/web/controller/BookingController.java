package com.linkz.seatreservation.web.controller;

import com.linkz.seatreservation.business.port.in.HoldSeatUseCase;
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

@RestController
public class BookingController {
    private final HoldSeatUseCase holdSeatUseCase;

    public BookingController(HoldSeatUseCase holdSeatUseCase) {
        this.holdSeatUseCase = holdSeatUseCase;
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
}
