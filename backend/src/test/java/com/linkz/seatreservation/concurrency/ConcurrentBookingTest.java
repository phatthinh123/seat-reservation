package com.linkz.seatreservation.concurrency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkz.seatreservation.business.domain.enums.BookingStatus;
import com.linkz.seatreservation.business.domain.enums.SeatStatus;
import com.linkz.seatreservation.business.port.in.ReconcilePaymentUseCase;
import com.linkz.seatreservation.business.port.out.PaymentGatewayPort;
import com.linkz.seatreservation.web.dto.BookingResponse;
import com.linkz.seatreservation.web.dto.HoldSeatRequest;
import com.linkz.seatreservation.web.dto.PaymentResponse;
import com.linkz.seatreservation.web.dto.WebhookEventDto;
import com.linkz.seatreservation.web.dto.response.SeatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
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
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("isDockerAvailable")
public class ConcurrentBookingTest {

    static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

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

    @Test
    void only_one_of_100_concurrent_requests_should_succeed() throws Exception {
        // Fetch seats to find an available seat ID (we use A1)
        ResponseEntity<SeatResponse[]> seatsResp = restTemplate.exchange(
            "/api/seats",
            HttpMethod.GET,
            new HttpEntity<>(createHeaders("test-user")),
            SeatResponse[].class
        );
        assertThat(seatsResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        SeatResponse[] seats = seatsResp.getBody();
        assertThat(seats).isNotNull();
        
        SeatResponse seatA1 = Arrays.stream(seats)
            .filter(s -> "A1".equals(s.label()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Seat A1 not found"));
        
        UUID seatId = seatA1.id();

        int n = 100;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        for (int i = 0; i < n; i++) {
            final String userId = "user-" + i;
            // Generate standard idempotency key deterministic to make sure it doesn't fail due to same idempotency key across different users (each has their own)
            String raw = userId + ":" + seatId;
            String idempotencyKey = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))
            );

            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    HttpHeaders headers = createHeaders(userId, idempotencyKey);
                    HoldSeatRequest request = new HoldSeatRequest(seatId);
                    ResponseEntity<BookingResponse> resp = restTemplate.postForEntity(
                        "/api/bookings",
                        new HttpEntity<>(request, headers),
                        BookingResponse.class
                    );
                    if (resp.getStatusCode() == HttpStatus.CREATED) {
                        successes.incrementAndGet();
                    } else if (resp.getStatusCode() == HttpStatus.CONFLICT) {
                        conflicts.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Ignore
                }
            });
        }

        ready.await();
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(15, TimeUnit.SECONDS);

        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(99);

        // Verify seat status is HELD in DB
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM seats WHERE id = ?",
            String.class,
            seatId
        );
        assertThat(status).isEqualTo("HELD");
    }

    @Test
    void same_idempotency_key_returns_same_booking() throws Exception {
        // Fetch A2
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

        HoldSeatRequest request = new HoldSeatRequest(seatId);
        HttpHeaders headers = createHeaders(userId, idempotencyKey);

        ResponseEntity<BookingResponse> first = restTemplate.postForEntity(
            "/api/bookings",
            new HttpEntity<>(request, headers),
            BookingResponse.class
        );
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<BookingResponse> second = restTemplate.postForEntity(
            "/api/bookings",
            new HttpEntity<>(request, headers),
            BookingResponse.class
        );
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(first.getBody().bookingId()).isEqualTo(second.getBody().bookingId());
    }

    @Test
    void webhook_processed_exactly_once_when_delivered_twice() throws Exception {
        // Clear status and delete bookings to ensure clean slate
        jdbcTemplate.update("UPDATE seats SET status = 'AVAILABLE'");
        jdbcTemplate.update("DELETE FROM bookings");

        ResponseEntity<SeatResponse[]> seatsResp = restTemplate.exchange(
            "/api/seats",
            HttpMethod.GET,
            new HttpEntity<>(createHeaders("test-user")),
            SeatResponse[].class
        );
        SeatResponse seat = Arrays.stream(seatsResp.getBody())
            .filter(s -> "A2".equals(s.label()))
            .findFirst()
            .orElseThrow();

        UUID seatId = seat.id();
        String userId = "webhook-user";

        // 1. Hold seat
        HoldSeatRequest holdReq = new HoldSeatRequest(seatId);
        ResponseEntity<BookingResponse> holdResp = restTemplate.postForEntity(
            "/api/bookings",
            new HttpEntity<>(holdReq, createHeaders(userId)),
            BookingResponse.class
        );
        UUID bookingId = holdResp.getBody().bookingId();

        // 2. Initiate Payment
        when(paymentGateway.initiatePayment(any(), any(), any(), anyBoolean())).thenReturn("external-pay-123");
        ResponseEntity<PaymentResponse> payResp = restTemplate.postForEntity(
            "/api/bookings/" + bookingId + "/payment",
            new HttpEntity<>(createHeaders(userId)),
            PaymentResponse.class
        );
        String paymentId = payResp.getBody().paymentId();

        // 3. Deliver Webhook Twice
        WebhookEventDto webhookDto = new WebhookEventDto("evt-dup-123", paymentId, bookingId.toString(), "SUCCESS");
        String webhookBody = objectMapper.writeValueAsString(webhookDto);
        String signature = hmacSha256(webhookBody, "test-secret");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Signature", signature);

        // First delivery
        ResponseEntity<Void> firstWeb = restTemplate.postForEntity(
            "/api/webhooks/payment",
            new HttpEntity<>(webhookBody, headers),
            Void.class
        );
        assertThat(firstWeb.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second delivery
        ResponseEntity<Void> secondWeb = restTemplate.postForEntity(
            "/api/webhooks/payment",
            new HttpEntity<>(webhookBody, headers),
            Void.class
        );
        assertThat(secondWeb.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify database state
        int processedCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM webhook_events WHERE event_id = 'evt-dup-123'",
            Integer.class
        );
        // Both are saved: first as PROCESSED, second as DUPLICATE (handled)
        assertThat(processedCount).isEqualTo(2);

        int processedAuditCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE entity_id = ? AND action = 'BOOKING_CONFIRMED'",
            Integer.class,
            bookingId.toString()
        );
        assertThat(processedAuditCount).isEqualTo(1);
    }

    @Test
    void expired_hold_releases_seat_back_to_available() throws Exception {
        // Clear everything first
        jdbcTemplate.update("UPDATE seats SET status = 'AVAILABLE'");
        jdbcTemplate.update("DELETE FROM bookings");

        ResponseEntity<SeatResponse[]> seatsResp = restTemplate.exchange(
            "/api/seats",
            HttpMethod.GET,
            new HttpEntity<>(createHeaders("test-user")),
            SeatResponse[].class
        );
        SeatResponse seat = Arrays.stream(seatsResp.getBody())
            .filter(s -> "A3".equals(s.label()))
            .findFirst()
            .orElseThrow();

        UUID seatId = seat.id();
        String userId = "expired-user";

        // Hold seat
        HoldSeatRequest holdReq = new HoldSeatRequest(seatId);
        ResponseEntity<BookingResponse> holdResp = restTemplate.postForEntity(
            "/api/bookings",
            new HttpEntity<>(holdReq, createHeaders(userId)),
            BookingResponse.class
        );
        UUID bookingId = holdResp.getBody().bookingId();

        // Check held status
        String heldStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM seats WHERE id = ?",
            String.class,
            seatId
        );
        assertThat(heldStatus).isEqualTo("HELD");

        // Manually expire hold in DB
        jdbcTemplate.update(
            "UPDATE bookings SET hold_expires_at = ? WHERE id = ?",
            LocalDateTime.now().minusMinutes(1),
            bookingId
        );

        // Run cleanup job
        reconciliationService.releaseExpiredHolds();

        // Verify seat is available again
        String finalStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM seats WHERE id = ?",
            String.class,
            seatId
        );
        assertThat(finalStatus).isEqualTo("AVAILABLE");

        // Verify booking status is EXPIRED
        String bookingStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM bookings WHERE id = ?",
            String.class,
            bookingId
        );
        assertThat(bookingStatus).isEqualTo("EXPIRED");
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
