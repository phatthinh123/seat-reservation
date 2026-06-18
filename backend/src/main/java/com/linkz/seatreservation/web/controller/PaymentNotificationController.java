package com.tpthinh.seatreservation.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpthinh.seatreservation.business.domain.exception.InvalidPaymentNotificationSignatureException;
import com.tpthinh.seatreservation.business.port.in.HandlePaymentNotificationUseCase;
import com.tpthinh.seatreservation.web.dto.PaymentNotificationDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.util.HexFormat;

@RestController
public class PaymentNotificationController {
    private static final Logger log = LoggerFactory.getLogger(PaymentNotificationController.class);

    private final HandlePaymentNotificationUseCase handleNotificationUseCase;
    private final ObjectMapper objectMapper;

    @Value("${webhook.secret:local-dev-secret}")
    private String webhookSecret;

    public PaymentNotificationController(HandlePaymentNotificationUseCase handleNotificationUseCase, ObjectMapper objectMapper) {
        this.handleNotificationUseCase = handleNotificationUseCase;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/api/webhooks/payment")
    public ResponseEntity<Void> handleWebhook(
            @RequestHeader("X-Signature") String signature,
            @RequestBody String rawBody) {
        
        log.info("Received payment notification webhook with signature: {}", signature);

        String expected = hmacSha256(rawBody, webhookSecret);
        if (signature == null || !MessageDigest.isEqual(expected.getBytes(), signature.getBytes())) {
            log.error("Invalid payment notification signature. Expected {}, got {}", expected, signature);
            throw new InvalidPaymentNotificationSignatureException("Webhook signature verification failed");
        }

        try {
            PaymentNotificationDto event = objectMapper.readValue(rawBody, PaymentNotificationDto.class);
            HandlePaymentNotificationUseCase.PaymentNotificationCommand cmd = new HandlePaymentNotificationUseCase.PaymentNotificationCommand(
                event.eventId(),
                event.paymentId(),
                event.bookingId(),
                event.status(),
                rawBody
            );
            handleNotificationUseCase.handleNotification(cmd);
        } catch (Exception e) {
            log.error("Error processing payment notification: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to process payment notification: " + e.getMessage(), e);
        }

        return ResponseEntity.ok().build();
    }

    private String hmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException("HMAC computation failed", e);
        }
    }
}
