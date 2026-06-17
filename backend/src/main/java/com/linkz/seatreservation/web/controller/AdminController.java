package com.linkz.seatreservation.web.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class AdminController {

    @GetMapping("/api/admin/pending-bookings")
    public ResponseEntity<List<Map<String, Object>>> getPendingBookings() {
        return ResponseEntity.ok(List.of(
            Map.of("id", "booking-uuid-1", "status", "PENDING")
        ));
    }
}
