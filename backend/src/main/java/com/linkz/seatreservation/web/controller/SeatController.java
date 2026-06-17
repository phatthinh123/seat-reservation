package com.linkz.seatreservation.web.controller;

import com.linkz.seatreservation.business.domain.enums.SeatStatus;
import com.linkz.seatreservation.web.dto.response.SeatResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class SeatController {

    @GetMapping("/api/seats")
    public ResponseEntity<List<SeatResponse>> getSeats() {
        return ResponseEntity.ok(List.of(
            new SeatResponse(UUID.fromString("11111111-1111-1111-1111-111111111111"), "A1", SeatStatus.AVAILABLE),
            new SeatResponse(UUID.fromString("22222222-2222-2222-2222-222222222222"), "A2", SeatStatus.AVAILABLE),
            new SeatResponse(UUID.fromString("33333333-3333-3333-3333-333333333333"), "A3", SeatStatus.AVAILABLE)
        ));
    }
}
