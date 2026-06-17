package com.linkz.seatreservation.web.controller;

import com.linkz.seatreservation.business.port.in.GetSeatsUseCase;
import com.linkz.seatreservation.web.dto.response.SeatResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
public class SeatController {
    private final GetSeatsUseCase getSeatsUseCase;

    public SeatController(GetSeatsUseCase getSeatsUseCase) {
        this.getSeatsUseCase = getSeatsUseCase;
    }

    @GetMapping("/api/seats")
    public ResponseEntity<List<SeatResponse>> getSeats() {
        var seats = getSeatsUseCase.getSeats();
        var responses = seats.stream().map(SeatResponse::from).toList();
        return ResponseEntity.ok(responses);
    }
}
