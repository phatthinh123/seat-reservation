package com.tpthinh.seatreservation.concurrency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpthinh.seatreservation.business.port.in.ReconcilePaymentUseCase;
import com.tpthinh.seatreservation.business.port.external.PaymentGatewayPort;
import com.tpthinh.seatreservation.web.dto.BookingResponse;
import com.tpthinh.seatreservation.web.dto.HoldSeatRequest;
import com.tpthinh.seatreservation.web.dto.PaymentResponse;
import com.tpthinh.seatreservation.web.dto.PaymentNotificationDto;
import com.tpthinh.seatreservation.web.dto.response.SeatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Integration tests requiring Docker (Testcontainers with PostgreSQL and Redis).
 * Skipped automatically when Docker is unavailable.
 *
 * All tests follow BDD structure:
 *   // Given — set up preconditions
 *   // When  — execute the action under test
 *   // Then  — assert expected outcomes
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
public class ConcurrentBookingTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("seatreservation_test")
        .withUsername("seat").withPassword("seat");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        public JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                .header("alg", "none")
                .claim("sub", token)
                .claim("realm_access", Map.of("roles", List.of("USER", "ADMIN")))
                .build();
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReconcilePaymentUseCase reconciliationService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PaymentGatewayPort paymentGateway;

    // ─── Happy Path ───────────────────────────────────────────────────────────

    /**
     * Happy path (concurrency):
     * When 100 users simultaneously try to hold the same seat,
     * exactly 1 request succeeds and the rest receive a 409 CONFLICT.
     * This proves the pessimistic-lock + Redis-lock chain works correctly.
     */
    @Test
    void testConcurrency_only1Request_shouldSucceed() throws Exception {
        // Given — seat A1 exists and is available
        ResponseEntity<SeatResponse[]> seatsResp = restTemplate.exchange(
            "/api/seats",
            HttpMethod.GET,
            new HttpEntity<>(createHeaders("test-user")),
            SeatResponse[].class
        );
        assertThat(seatsResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        SeatResponse seatA1 = Arrays.stream(seatsResp.getBody())
            .filter(s -> "A1".equals(s.label()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Seat A1 not found"));
        UUID seatId = seatA1.id();

        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        // When — 100 threads all try to hold the same seat simultaneously
        for (int i = 0; i < threads; i++) {
            final String userId = "user-" + i;
            String raw = userId + ":" + seatId;
            String idempotencyKey = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))
            );
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    ResponseEntity<BookingResponse> resp = restTemplate.postForEntity(
                        "/api/bookings",
                        new HttpEntity<>(new HoldSeatRequest(seatId), createHeaders(userId, idempotencyKey)),
                        BookingResponse.class
                    );
                    if (resp.getStatusCode() == HttpStatus.CREATED)   successes.incrementAndGet();
                    else if (resp.getStatusCode() == HttpStatus.CONFLICT) conflicts.incrementAndGet();
                } catch (Exception ignored) {}
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(15, TimeUnit.SECONDS);

        // Then — exactly 1 succeeds, 99 are rejected, seat is HELD in DB
        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(99);
        String dbStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM seats WHERE id = ?", String.class, seatId
        );
        assertThat(dbStatus).isEqualTo("HELD");
    }

    /**
     * Happy path (idempotency):
     * When the same Idempotency-Key is sent twice for the same seat,
     * both responses return HTTP 201 with the identical booking ID
     * — demonstrating safe at-most-once booking creation.
     */
    @Test
    void testIdempotency_sameKeySubmittedTwice_shouldReturnSameBooking() throws Exception {
        // Given — seat A2 is available, client has a stable idempotency key
        ResponseEntity<SeatResponse[]> seatsResp = restTemplate.exchange(
            "/api/seats",
            HttpMethod.GET,
            new HttpEntity<>(createHeaders("test-user")),
            SeatResponse[].class
        );
        SeatResponse seatA2 = Arrays.stream(seatsResp.getBody())
            .filter(s -> "A2".equals(s.label()))
            .findFirst()
            .orElseThrow();
        UUID seatId = seatA2.id();
        String idempotencyKey = UUID.randomUUID().toString();
        String userId = "idemp-user";

        // When — the same request is submitted twice with the same idempotency key
        ResponseEntity<BookingResponse> first = restTemplate.postForEntity(
            "/api/bookings",
            new HttpEntity<>(new HoldSeatRequest(seatId), createHeaders(userId, idempotencyKey)),
            BookingResponse.class
        );
        ResponseEntity<BookingResponse> second = restTemplate.postForEntity(
            "/api/bookings",
            new HttpEntity<>(new HoldSeatRequest(seatId), createHeaders(userId, idempotencyKey)),
            BookingResponse.class
        );

        // Then — both return 201 and carry the exact same booking ID
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().bookingId()).isEqualTo(second.getBody().bookingId());
    }

    /**
     * Happy path (webhook idempotency):
     * When the payment gateway delivers the same webhook event twice,
     * the booking is confirmed exactly once and the audit log contains
     * only a single BOOKING_CONFIRMED entry.
     */
    @Test
    void testWebhookIdempotency_duplicateWebhookDelivered_shouldProcessOnlyOnce() throws Exception {
        // Given — a pending booking exists with an associated payment
        jdbcTemplate.update("UPDATE seats SET status = 'AVAILABLE'");
        jdbcTemplate.update("DELETE FROM bookings");

        SeatResponse seat = Arrays.stream(
            restTemplate.exchange("/api/seats", HttpMethod.GET,
                new HttpEntity<>(createHeaders("test-user")), SeatResponse[].class).getBody()
        ).filter(s -> "A4".equals(s.label())).findFirst().orElseThrow();

        UUID seatId = seat.id();
        String userId = "webhook-user";

        ResponseEntity<BookingResponse> holdResp = restTemplate.postForEntity(
            "/api/bookings",
            new HttpEntity<>(new HoldSeatRequest(seatId), createHeaders(userId)),
            BookingResponse.class
        );
        UUID bookingId = holdResp.getBody().bookingId();

        when(paymentGateway.initiatePayment(any(), any(), any(), anyBoolean(), anyBoolean())).thenReturn("external-pay-123");
        ResponseEntity<PaymentResponse> payResp = restTemplate.postForEntity(
            "/api/bookings/" + bookingId + "/payment",
            new HttpEntity<>(createHeaders(userId)),
            PaymentResponse.class
        );
        String paymentId = payResp.getBody().paymentId();

        String webhookBody = objectMapper.writeValueAsString(
            new PaymentNotificationDto("evt-dup-123", paymentId, bookingId.toString(), "SUCCESS")
        );
        String signature = hmacSha256(webhookBody, "test-secret");
        HttpHeaders webhookHeaders = new HttpHeaders();
        webhookHeaders.setContentType(MediaType.APPLICATION_JSON);
        webhookHeaders.set("X-Signature", signature);

        // When — the same webhook is delivered twice
        ResponseEntity<Void> first  = restTemplate.postForEntity("/api/webhooks/payment", new HttpEntity<>(webhookBody, webhookHeaders), Void.class);
        ResponseEntity<Void> second = restTemplate.postForEntity("/api/webhooks/payment", new HttpEntity<>(webhookBody, webhookHeaders), Void.class);

        // Then — both deliveries return 200 (ack), but BOOKING_CONFIRMED is written only once
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

        int webhookRows = 0;
        String webhookStatus = "";
        for (int i = 0; i < 20; i++) {
            webhookRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_notifications WHERE event_id = 'evt-dup-123'",
                Integer.class
            );
            if (webhookRows == 1) {
                webhookStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM payment_notifications WHERE event_id = 'evt-dup-123'",
                    String.class
                );
                if ("DUPLICATE".equals(webhookStatus)) break;
            }
            Thread.sleep(100);
        }
        assertThat(webhookRows).isEqualTo(1);
        assertThat(webhookStatus).isEqualTo("DUPLICATE");

        int confirmedAuditCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE entity_id = ? AND action = 'BOOKING_CONFIRMED'",
            Integer.class, bookingId.toString()
        );
        assertThat(confirmedAuditCount).isEqualTo(1);
    }

    // ─── Non-Happy Path ───────────────────────────────────────────────────────

    /**
     * Non-happy path (hold expiry):
     * When a seat hold expires without payment, the cleanup job
     * must release the seat back to AVAILABLE and mark the booking EXPIRED.
     * This validates the scheduled hold-release mechanism.
     */
    @Test
    void testHoldExpiry_holdTimesOutWithoutPayment_shouldReleaseSeatToAvailable() throws Exception {
        // Given — a seat is held, then the hold_expires_at is artificially set in the past
        jdbcTemplate.update("UPDATE seats SET status = 'AVAILABLE'");
        jdbcTemplate.update("DELETE FROM bookings");

        SeatResponse seat = Arrays.stream(
            restTemplate.exchange("/api/seats", HttpMethod.GET,
                new HttpEntity<>(createHeaders("test-user")), SeatResponse[].class).getBody()
        ).filter(s -> "A3".equals(s.label())).findFirst().orElseThrow();

        UUID seatId = seat.id();
        ResponseEntity<BookingResponse> holdResp = restTemplate.postForEntity(
            "/api/bookings",
            new HttpEntity<>(new HoldSeatRequest(seatId), createHeaders("expired-user")),
            BookingResponse.class
        );
        UUID bookingId = holdResp.getBody().bookingId();

        String heldStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM seats WHERE id = ?", String.class, seatId
        );
        assertThat(heldStatus).isEqualTo("HELD");

        // When — the hold is expired in the DB and the cleanup job runs
        jdbcTemplate.update(
            "UPDATE bookings SET hold_expires_at = ? WHERE id = ?",
            LocalDateTime.now().minusMinutes(1), bookingId
        );
        reconciliationService.releaseExpiredHolds();

        // Then — seat is AVAILABLE again and booking is EXPIRED
        String seatStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM seats WHERE id = ?", String.class, seatId
        );
        assertThat(seatStatus).isEqualTo("AVAILABLE");

        String bookingStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM bookings WHERE id = ?", String.class, bookingId
        );
        assertThat(bookingStatus).isEqualTo("EXPIRED");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders createHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders createHeaders(String token, String idempotencyKey) {
        HttpHeaders headers = createHeaders(token);
        headers.set("Idempotency-Key", idempotencyKey);
        return headers;
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
