package com.linkz.seatreservation.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkz.seatreservation.business.domain.exception.InvalidWebhookSignatureException;
import com.linkz.seatreservation.business.port.in.HandleWebhookUseCase;
import com.linkz.seatreservation.web.dto.WebhookEventDto;
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
public class WebhookController {
    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final HandleWebhookUseCase handleWebhookUseCase;
    private final ObjectMapper objectMapper;

    @Value("${webhook.secret:local-dev-secret}")
    private String webhookSecret;

    public WebhookController(HandleWebhookUseCase handleWebhookUseCase, ObjectMapper objectMapper) {
        this.handleWebhookUseCase = handleWebhookUseCase;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/api/webhooks/payment")
    public ResponseEntity<Void> handleWebhook(
            @RequestHeader("X-Signature") String signature,
            @RequestBody String rawBody) {
        
        log.info("Received payment webhook with signature: {}", signature);

        String expected = hmacSha256(rawBody, webhookSecret);
        if (signature == null || !MessageDigest.isEqual(expected.getBytes(), signature.getBytes())) {
            log.error("Invalid webhook signature. Expected {}, got {}", expected, signature);
            throw new InvalidWebhookSignatureException("Webhook signature verification failed");
        }

        try {
            WebhookEventDto event = objectMapper.readValue(rawBody, WebhookEventDto.class);
            HandleWebhookUseCase.WebhookEventCommand cmd = new HandleWebhookUseCase.WebhookEventCommand(
                event.eventId(),
                event.paymentId(),
                event.bookingId(),
                event.status(),
                rawBody
            );
            handleWebhookUseCase.handleWebhook(cmd);
        } catch (Exception e) {
            log.error("Error processing webhook: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to process webhook: " + e.getMessage(), e);
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
