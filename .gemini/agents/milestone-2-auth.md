# Agent: Milestone 2 — Authentication

## Your Role
You are the auth agent. Milestone 1 is complete. Your job is to wire Keycloak, Spring Security,
and Angular auth so an authenticated user can hit `/api/seats` with a valid JWT.

## Reference Files
- `IMPLEMENTATION_PLAN.md` — Section 3.1, 3.2, 4.9 (error codes)
- `.gemini/skills/spring-boot-patterns.md` — SecurityConfig, JWT converter

## What You Must Deliver

### 1. Keycloak Realm Export (keycloak/realm-export.json)

A complete Keycloak realm JSON containing:
- Realm name: `seat-reservation`
- Client: `seat-reservation-app`
  - publicClient: true
  - redirectUris: `["http://localhost:4200/*"]`
  - webOrigins: `["http://localhost:4200"]`
- Realm roles: `USER`, `ADMIN`
- Users:
  - `user@linkz.com` / `User1234!` → assigned role `USER`
  - `admin@linkz.com` / `Admin1234!` → assigned role `ADMIN`
- Session settings:
  - `ssoSessionMaxLifespan`: 7776000 (90 days in seconds)
  - `ssoSessionIdleTimeout`: 7776000

Use Keycloak 24.x realm export format. The file will be auto-imported via Docker Compose
`start-dev --import-realm`.

### 2. Spring Security (backend/src/main/java/.../web/config/SecurityConfig.java)

**Note:** This goes in `web/` not `business/` — it's infrastructure config.

Implement exactly as shown in `.gemini/skills/spring-boot-patterns.md`:
- STATELESS session
- JWT resource server with Keycloak role extractor from `realm_access.roles`
- Public: `POST /api/webhooks/**`
- ADMIN only: `/api/admin/**`
- All others: authenticated

Also add CORS config:
```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of("http://localhost:4200"));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
}
```

### 3. Global Exception Handler (web/config/GlobalExceptionHandler.java)

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(SeatUnavailableException.class)
    public ResponseEntity<ErrorResponse> onSeatUnavailable(SeatUnavailableException e) {
        return ResponseEntity.status(409).body(new ErrorResponse("SEAT_UNAVAILABLE", e.getMessage(), Instant.now()));
    }
    @ExceptionHandler(BookingNotFoundException.class)
    public ResponseEntity<ErrorResponse> onNotFound(BookingNotFoundException e) {
        return ResponseEntity.status(404).body(new ErrorResponse("BOOKING_NOT_FOUND", e.getMessage(), Instant.now()));
    }
    @ExceptionHandler(BookingNotOwnedException.class)
    public ResponseEntity<ErrorResponse> onNotOwned(BookingNotOwnedException e) {
        return ResponseEntity.status(403).body(new ErrorResponse("BOOKING_NOT_OWNED", e.getMessage(), Instant.now()));
    }
    // Add all 7 error codes from IMPLEMENTATION_PLAN.md Section 4.9
}
```

### 4. Minimal Seat Endpoint (to verify auth works)

Create just enough to verify auth:
- `business/domain/model/Seat.java` — record with id, label, status
- `business/domain/enums/SeatStatus.java` — AVAILABLE, HELD, RESERVED
- `web/dto/response/SeatResponse.java`
- `web/controller/SeatController.java`:
  ```java
  @GetMapping("/api/seats")
  public ResponseEntity<List<SeatResponse>> getSeats() {
      // For now: return hardcoded list — full impl in Milestone 3
      return ResponseEntity.ok(List.of(
          new SeatResponse(null, "A1", SeatStatus.AVAILABLE),
          new SeatResponse(null, "A2", SeatStatus.AVAILABLE),
          new SeatResponse(null, "A3", SeatStatus.AVAILABLE)
      ));
  }
  ```

### 5. Angular Auth (frontend/src/app/)

Install Keycloak JS adapter:
```bash
npm install keycloak-js@24
```

Create `core/auth/keycloak.service.ts`:
```typescript
@Injectable({ providedIn: 'root' })
export class KeycloakService {
  private keycloak = new Keycloak({
    url: 'http://localhost:8180',
    realm: 'seat-reservation',
    clientId: 'seat-reservation-app'
  });
  
  async init(): Promise<void> {
    await this.keycloak.init({ onLoad: 'login-required', checkLoginIframe: false });
  }
  
  getToken(): string { return this.keycloak.token ?? ''; }
  logout(): void { this.keycloak.logout({ redirectUri: 'http://localhost:4200' }); }
  hasRole(role: string): boolean { return this.keycloak.hasRealmRole(role); }
}
```

Create `core/auth/auth.interceptor.ts` — attaches `Authorization: Bearer <token>` to all requests.

Create `core/auth/auth.guard.ts` — redirects to Keycloak if not authenticated.

Create `core/auth/admin.guard.ts` — redirects to /seats if not ADMIN role.

Initialize KeycloakService in `APP_INITIALIZER` in `app.config.ts`.

## Verification Steps

```bash
# 1. Start Keycloak
docker compose up keycloak -d
# Wait ~30s for startup

# 2. Verify realm imported
curl http://localhost:8180/realms/seat-reservation/.well-known/openid-configuration
# Should return JSON with issuer = "http://localhost:8180/realms/seat-reservation"

# 3. Get token for user@linkz.com
curl -X POST http://localhost:8180/realms/seat-reservation/protocol/openid-connect/token \
  -d "client_id=seat-reservation-app&username=user@linkz.com&password=User1234!&grant_type=password"
# Copy the access_token

# 4. Hit protected endpoint
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/seats
# Should return 200 with 3 seats

# 5. Hit protected endpoint without token
curl http://localhost:8080/api/seats
# Should return 401

# 6. Admin endpoint with non-admin token
curl -H "Authorization: Bearer <user-token>" http://localhost:8080/api/admin/pending-bookings
# Should return 403
```

## Definition of Done

✅ Keycloak starts and realm is auto-imported (both users present)
✅ `GET /api/seats` returns 401 without token, 200 with valid JWT
✅ `GET /api/admin/*` returns 403 with USER token, 200 with ADMIN token
✅ Angular loads, redirects to Keycloak, user can log in
✅ After login, Angular stores token and interceptor attaches it
