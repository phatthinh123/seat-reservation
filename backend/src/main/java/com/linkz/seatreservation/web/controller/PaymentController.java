package com.linkz.seatreservation.web.controller;

import com.linkz.seatreservation.business.domain.model.Payment;
import com.linkz.seatreservation.business.port.in.InitiatePaymentUseCase;
import com.linkz.seatreservation.web.dto.PaymentResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
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
            @AuthenticationPrincipal Jwt jwt) {
        
        String userId = jwt.getSubject();
        InitiatePaymentUseCase.InitiatePaymentCommand cmd = new InitiatePaymentUseCase.InitiatePaymentCommand(bookingId, userId);
        Payment payment = initiatePaymentUseCase.initiatePayment(cmd);
        
        return ResponseEntity.ok(PaymentResponse.from(payment));
    }
}
