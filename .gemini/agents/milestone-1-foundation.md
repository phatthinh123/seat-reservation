# Agent: Milestone 1 — Foundation & Infrastructure

## Your Role
You are the foundation agent. Your job is to scaffold the entire project infrastructure
so all subsequent milestone agents can work on a running skeleton.

## Project Location
All code goes in: `\\wsl.localhost\Ubuntu\home\tpthinh\playground\seat-reservation\seat-reservation`

## Reference Files (read these first)
- `IMPLEMENTATION_PLAN.md` — full design decisions, ports, schema
- `.gemini/skills/spring-boot-patterns.md` — application.yml template, dependencies

## What You Must Deliver

### 1. Gradle Multi-Project Build

`settings.gradle`:
```groovy
rootProject.name = 'seat-reservation'
include 'backend', 'mock-payment-service'
```

Root `build.gradle`:
```groovy
subprojects {
    apply plugin: 'java'
    java { sourceCompatibility = JavaVersion.VERSION_17 }
    repositories { mavenCentral() }
}
```

`backend/build.gradle` — Spring Boot 3.x with ALL required dependencies:
- spring-boot-starter-web
- spring-boot-starter-data-jpa
- spring-boot-starter-security
- spring-boot-starter-cache
- spring-boot-starter-validation
- spring-boot-starter-actuator
- spring-security-oauth2-resource-server
- spring-security-oauth2-jose
- flyway-core
- postgresql driver
- redisson-spring-boot-starter (3.x compatible)
- spring-boot-starter-data-redis
- lombok
- spring-boot-starter-test + testcontainers (test scope)

`mock-payment-service/build.gradle` — Spring Boot, Spring Web only:
- spring-boot-starter-web
- lombok

### 2. Docker Compose (docker-compose.yml in project root)

Services (exact ports from IMPLEMENTATION_PLAN.md):
- `postgres`: postgres:15, port 5432, db=seatreservation, user=seat, pass=seat
- `redis`: redis:7-alpine, port 6379
- `keycloak`: quay.io/keycloak/keycloak:24.0, port 8180, admin/admin
  - volume mount: `./keycloak/realm-export.json:/opt/keycloak/data/import/realm-export.json`
  - command: `start-dev --import-realm`
- `mock-payment-service`: build from `./mock-payment-service`, port 9090
  - env: WEBHOOK_URL, WEBHOOK_SECRET
- `backend`: build from `./backend`, port 8080
  - depends_on: postgres, redis, keycloak, mock-payment-service
  - env: all from .env.example
- `frontend`: build from `./frontend`, port 4200
  - depends_on: backend

### 3. Flyway Migrations (backend/src/main/resources/db/migration/)

V1__create_seats.sql through V6__seed_seats.sql exactly as in IMPLEMENTATION_PLAN.md Section 7.
Include the partial unique index in V2.

### 4. Dockerfiles

`backend/Dockerfile`:
```dockerfile
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

`mock-payment-service/Dockerfile`: same pattern

`frontend/Dockerfile`:
```dockerfile
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist/seat-reservation /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
```

### 5. Minimal Spring Boot Application

Create `SeatReservationApplication.java` with `@SpringBootApplication`.
Create `application.yml` using the template from `.gemini/skills/spring-boot-patterns.md`.
Create `.env.example` with all variables documented.

### 6. Angular Scaffold

Initialize Angular 17 in `frontend/`:
```bash
npx -y @angular/cli@17 new seat-reservation --directory ./ \
  --routing true --style css --standalone true --skip-git true
```

### 7. Mock Payment Service Skeleton

Create `MockPaymentApplication.java` — just `@SpringBootApplication`.
Create `application.yml` — `server.port=9090`.

## Verification Steps (run these in order)

```bash
# 1. Build backend
cd backend && ../gradlew build -x test

# 2. Build mock payment service  
cd mock-payment-service && ../gradlew build -x test

# 3. Start infrastructure only
docker compose up postgres redis -d

# 4. Run migrations
cd backend && ../gradlew flywayMigrate

# 5. Verify tables created
docker exec seat-reservation-postgres-1 psql -U seat -d seatreservation -c '\dt'
# Should show: seats, bookings, payment_transactions, webhook_events, audit_logs

# 6. Verify 3 seats seeded
docker exec seat-reservation-postgres-1 psql -U seat -d seatreservation -c 'SELECT * FROM seats;'
# Should show: A1, A2, A3 all AVAILABLE
```

## Definition of Done

✅ `./gradlew build -x test` succeeds for backend and mock-payment-service
✅ `docker compose up postgres redis -d` starts without error
✅ Flyway migrations run: 5 tables + 3 seat rows exist
✅ Angular project created with `npm install` working
✅ `.env.example` documents every environment variable
✅ `IMPLEMENTATION_PLAN.md` moved/copied into project root
