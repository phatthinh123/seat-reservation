package com.linkz.seatreservation.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkz.seatreservation.business.port.in.HandleWebhookUseCase;
import com.linkz.seatreservation.web.controller.WebhookController;
import com.linkz.seatreservation.web.dto.WebhookEventDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
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

@WebMvcTest(WebhookController.class)
@ActiveProfiles("test")
@Import(com.linkz.seatreservation.web.config.SecurityConfig.class)
class WebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private HandleWebhookUseCase handleWebhookUseCase;

    @MockBean
    private JwtDecoder jwtDecoder;

    private final String testSecret = "test-secret";

    @Test
    void webhook_withValidSignature_shouldReturn200() throws Exception {
        WebhookEventDto dto = new WebhookEventDto(
            "evt-123",
            "pay-123",
            UUID.randomUUID().toString(),
            "SUCCESS"
        );
        String body = objectMapper.writeValueAsString(dto);
        String signature = hmacSha256(body, testSecret);

        doNothing().when(handleWebhookUseCase).handleWebhook(any());

        mockMvc.perform(post("/api/webhooks/payment")
                .header("X-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void webhook_withInvalidSignature_shouldReturn400() throws Exception {
        WebhookEventDto dto = new WebhookEventDto(
            "evt-123",
            "pay-123",
            UUID.randomUUID().toString(),
            "SUCCESS"
        );
        String body = objectMapper.writeValueAsString(dto);
        String signature = "invalid-signature";

        mockMvc.perform(post("/api/webhooks/payment")
                .header("X-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void webhook_withMissingSignature_shouldReturn400() throws Exception {
        WebhookEventDto dto = new WebhookEventDto(
            "evt-123",
            "pay-123",
            UUID.randomUUID().toString(),
            "SUCCESS"
        );
        String body = objectMapper.writeValueAsString(dto);

        mockMvc.perform(post("/api/webhooks/payment")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());
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
