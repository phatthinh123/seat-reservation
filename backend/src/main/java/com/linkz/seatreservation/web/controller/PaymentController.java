package com.tpthinh.seatreservation.web.controller;

import com.tpthinh.seatreservation.business.domain.model.Payment;
import com.tpthinh.seatreservation.business.port.in.InitiatePaymentUseCase;
import com.tpthinh.seatreservation.web.dto.PaymentResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
public class PaymentController {
    private final InitiatePaymentUseCase initiatePaymentUseCase;

    public PaymentController(InitiatePaymentUseCase initiatePaymentUseCase) {
        this.initiatePaymentUseCase = initiatePaymentUseCase;
    }

    @PostMapping("/api/bookings/{id}/payment")
    public ResponseEntity<PaymentResponse> initiatePayment(
            @PathVariable("id") UUID bookingId,
            @RequestBody(required = false) InitiatePaymentRequest body,
            @AuthenticationPrincipal Jwt jwt) {

        String userId = jwt.getSubject();
        boolean simulateFail = body != null && body.simulateFail();
        InitiatePaymentUseCase.InitiatePaymentCommand cmd =
            new InitiatePaymentUseCase.InitiatePaymentCommand(bookingId, userId, simulateFail);
        Payment payment = initiatePaymentUseCase.initiatePayment(cmd);

        return ResponseEntity.ok(PaymentResponse.from(payment));
    }

    /** Thin request DTO — keeps controller free of business logic. */
    record InitiatePaymentRequest(boolean simulateFail) {}
}
