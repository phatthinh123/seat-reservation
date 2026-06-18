package com.tpthinh.seatreservation.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpthinh.seatreservation.business.port.in.HandlePaymentNotificationUseCase;
import com.tpthinh.seatreservation.web.controller.PaymentNotificationController;
import com.tpthinh.seatreservation.web.dto.PaymentNotificationDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test for PaymentNotificationController — verifies HMAC signature enforcement at the HTTP layer.
 * No business logic is exercised here; HandlePaymentNotificationUseCase is mocked.
 *
 * All tests follow BDD structure:
 *   // Given — set up preconditions
 *   // When  — perform the HTTP request
 *   // Then  — assert the expected HTTP response code
 */
@WebMvcTest(PaymentNotificationController.class)
@ActiveProfiles("test")
@Import(com.tpthinh.seatreservation.web.config.SecurityConfig.class)
class PaymentNotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private HandlePaymentNotificationUseCase handleNotificationUseCase;

    @MockBean
    private JwtDecoder jwtDecoder;

    private final String testSecret = "test-secret";

    // ─── Happy Path ───────────────────────────────────────────────────────────

    /**
     * Happy path:
     * When a webhook arrives with a valid HMAC-SHA256 signature that matches
     * the shared secret, the request is accepted with HTTP 200.
     */
    @Test
    void testWebhook_validHmacSignature_shouldReturn200() throws Exception {
        // Given — a well-formed webhook payload with a correct HMAC signature
        PaymentNotificationDto dto = new PaymentNotificationDto("evt-ok", "pay-ok", UUID.randomUUID().toString(), "SUCCESS");
        String body = objectMapper.writeValueAsString(dto);
        String signature = hmacSha256(body, testSecret);

        doNothing().when(handleNotificationUseCase).handleNotification(any());

        // When / Then — request with valid signature → 200 OK
        mockMvc.perform(post("/api/webhooks/payment")
                .header("X-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk());
    }

    // ─── Non-Happy Path ───────────────────────────────────────────────────────

    /**
     * Non-happy path (tampered signature):
     * When the X-Signature header does not match the HMAC of the body
     * (e.g., the payload was tampered with, or a wrong secret was used),
     * the request must be rejected with HTTP 400 to prevent spoofed webhooks.
     */
    @Test
    void testWebhook_invalidHmacSignature_shouldReturn400() throws Exception {
        // Given — a real payload but a deliberately wrong signature
        PaymentNotificationDto dto = new PaymentNotificationDto("evt-bad", "pay-bad", UUID.randomUUID().toString(), "SUCCESS");
        String body = objectMapper.writeValueAsString(dto);
        String tamperedSignature = "deadbeefdeadbeef";

        // When / Then — request with invalid signature → 400 Bad Request
        mockMvc.perform(post("/api/webhooks/payment")
                .header("X-Signature", tamperedSignature)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());
    }

    /**
     * Non-happy path (missing signature):
     * When no X-Signature header is present at all (e.g., a direct probe
     * from an unknown caller), the request must be rejected with HTTP 400.
     */
    @Test
    void testWebhook_missingSignatureHeader_shouldReturn400() throws Exception {
        // Given — a real payload but no X-Signature header
        PaymentNotificationDto dto = new PaymentNotificationDto("evt-nosig", "pay-nosig", UUID.randomUUID().toString(), "SUCCESS");
        String body = objectMapper.writeValueAsString(dto);

        // When / Then — request without X-Signature → 400 Bad Request
        mockMvc.perform(post("/api/webhooks/payment")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

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
