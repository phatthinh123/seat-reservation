package com.linkz.seatreservation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Integration test requiring Docker (for Redis via Testcontainers).
 * Skipped when Docker is unavailable.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
    "spring.cache.type=none"
})
@EnabledIfSystemProperty(named = "docker.available", matches = "true")
class SeatReservationApplicationTests {

    @Test
    void contextLoads() {
        // Basic context load test - requires Redis for Redisson
    }
}
