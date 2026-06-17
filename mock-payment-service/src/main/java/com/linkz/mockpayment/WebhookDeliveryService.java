package com.linkz.mockpayment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service
public class WebhookDeliveryService {
    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${webhook.secret:local-dev-secret}")
    private String webhookSecret;

    @Async("webhookExecutor")
    public void scheduleDelivery(String paymentId, String bookingId, String callbackUrl, boolean simulateFail) {
        log.info("Scheduling webhook delivery for payment {} (booking {}), simulateFail={}", paymentId, bookingId, simulateFail);
        try {
            boolean actualDelay = SimulationController.nextDelay.getAndSet(false);
            if (actualDelay) {
                log.info("Simulating delay: sleeping 60 seconds for payment {}", paymentId);
                Thread.sleep(60000);
            } else {
                Thread.sleep(2000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        String eventId = UUID.randomUUID().toString();
        String status = simulateFail ? "FAILED" : "SUCCESS";
        String payload = String.format(
            "{\"eventId\":\"%s\",\"paymentId\":\"%s\",\"bookingId\":\"%s\",\"status\":\"%s\"}",
            eventId, paymentId, bookingId, status
        );

        String signature = hmacSha256(payload, webhookSecret);

        // Retry up to 3x on non-200 with exponential backoff (1s, 2s, 4s)
        int maxRetries = 3;
        int delay = 1000;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(callbackUrl))
                        .header("Content-Type", "application/json")
                        .header("X-Signature", signature)
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    log.info("Webhook delivered successfully on attempt {} for payment {}", attempt, paymentId);
                    PaymentController.updatePaymentStatus(paymentId, simulateFail ? PaymentStatus.FAILED : PaymentStatus.SUCCESS);
                    return;
                } else {
                    log.warn("Webhook delivery returned status {} on attempt {} for payment {}", response.statusCode(), attempt, paymentId);
                }
            } catch (Exception e) {
                log.warn("Error delivering webhook on attempt {} for payment {}: {}", attempt, paymentId, e.getMessage());
            }

            if (attempt < maxRetries) {
                try {
                    Thread.sleep(delay);
                    delay *= 2;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.error("Failed to deliver webhook for payment {} after {} attempts", paymentId, maxRetries);
        PaymentController.updatePaymentStatus(paymentId, PaymentStatus.FAILED);
    }

    private String hmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes()));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException(e);
        }
    }
}
