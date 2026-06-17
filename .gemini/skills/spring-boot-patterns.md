# Skill: Spring Boot Patterns

## Project Setup

- Java 17, Spring Boot 3.x, Gradle
- Key dependencies: spring-boot-starter-web, spring-boot-starter-data-jpa,
  spring-boot-starter-security, spring-boot-starter-cache, spring-boot-starter-validation,
  spring-security-oauth2-resource-server, flyway-core, postgresql, redisson-spring-boot-starter,
  spring-data-redis

## Security Config (JWT Resource Server — no Keycloak lib needed)

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakConverter())))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(POST, "/api/webhooks/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            );
        return http.build();
    }
    
    // Extract Keycloak roles from realm_access.roles claim
    @Bean
    public JwtAuthenticationConverter keycloakConverter() {
        JwtGrantedAuthoritiesConverter conv = new JwtGrantedAuthoritiesConverter();
        conv.setAuthoritiesClaimName("realm_access.roles");
        conv.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter jwtConv = new JwtAuthenticationConverter();
        jwtConv.setJwtGrantedAuthoritiesConverter(conv);
        return jwtConv;
    }
}
```

## Redis Distributed Lock (Redisson)

```java
// adapter/lock/RedissonLockAdapter.java
@Component
public class RedissonLockAdapter implements DistributedLockPort {
    private final RedissonClient redisson;
    
    @Override
    public boolean tryLock(String key, long waitMs, long ttlMs) {
        RLock lock = redisson.getLock("seat-lock:" + key);
        try {
            return lock.tryLock(waitMs, ttlMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    @Override
    public void unlock(String key) {
        RLock lock = redisson.getLock("seat-lock:" + key);
        if (lock.isHeldByCurrentThread()) lock.unlock();
    }
}
```

## Pessimistic Locking (JPA)

```java
// adapter/persistence/SeatJpaRepository.java (Spring Data interface)
public interface SeatJpaRepository extends JpaRepository<SeatEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SeatEntity s WHERE s.id = :id")
    Optional<SeatEntity> findByIdForUpdate(@Param("id") UUID id);
}
```

## Redis Cache

```java
// application.yml
spring:
  cache:
    type: redis
  data.redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}

# In business/service or web/controller:
@Cacheable(value = "seats", key = "'all'")
public List<SeatResponse> getAllSeats() { ... }

@CacheEvict(value = "seats", allEntries = true)
public void holdSeat(...) { ... }

# Cache TTL config:
@Bean
public RedisCacheConfiguration cacheConfig() {
    return RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(Duration.ofSeconds(2));
}
```

## Scheduled Jobs

```java
@Component
public class SeatHoldCleanupJob {
    private final BookingRepositoryPort bookingRepo;
    private final AuditPort audit;
    
    @Scheduled(fixedDelay = 60_000) // every 1 minute
    @Transactional
    public void releaseExpiredHolds() {
        List<Booking> expired = bookingRepo.findExpiredPending(LocalDateTime.now());
        for (Booking b : expired) {
            // update booking + seat + audit in same transaction via adapter
        }
    }
}
```

## Webhook HMAC Verification

```java
@PostMapping("/api/webhooks/payment")
public ResponseEntity<Void> handleWebhook(
        @RequestHeader("X-Signature") String signature,
        @RequestBody String rawBody) {
    // Verify HMAC-SHA256
    String expected = hmacSha256(rawBody, webhookSecret);
    if (!MessageDigest.isEqual(expected.getBytes(), signature.getBytes())) {
        return ResponseEntity.badRequest().build();
    }
    handleWebhookUseCase.handleWebhook(parseEvent(rawBody));
    return ResponseEntity.ok().build();
}

private String hmacSha256(String data, String secret) {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(data.getBytes()));
}
```

## Exception Handling

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(SeatUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleSeatUnavailable(SeatUnavailableException e) {
        return ResponseEntity.status(409)
            .body(new ErrorResponse("SEAT_UNAVAILABLE", e.getMessage(), Instant.now()));
    }
    // ... other handlers
}
```

## application.yml template

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:seatreservation}
    username: ${DB_USER:seat}
    password: ${DB_PASS:seat}
  jpa:
    hibernate.ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
    locations: classpath:db/migration
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER:http://localhost:8180/realms/seat-reservation}
  data.redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
  cache.type: redis

webhook:
  secret: ${WEBHOOK_SECRET:local-dev-secret}

mock-payment:
  url: ${MOCK_PAYMENT_URL:http://localhost:9090}
  callback-url: ${BACKEND_URL:http://localhost:8080}/api/webhooks/payment

server:
  port: 8080

logging:
  level:
    com.linkz: DEBUG
```
