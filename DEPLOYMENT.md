# Deployment Guide

## Prerequisites

Before you start, ensure the following tools are installed on your machine:

| Tool | Minimum Version | Check Command |
|------|----------------|---------------|
| Docker Engine | 24+ | `docker --version` |
| Docker Compose | 2.20+ (bundled with Docker Desktop) | `docker compose version` |
| Git | any | `git --version` |

> **Note:** You do **not** need Java, Node.js, or Gradle installed locally. Every build step runs inside Docker.

---

## Step-by-Step Deployment

### 1. Clone the Repository

```bash
git clone <your-repo-url>
cd seat-reservation
```

### 2. Configure Environment Variables

```bash
cp .env.example .env
```

The defaults in `.env.example` are ready for local development — you do not need to change anything to run locally.

If you want to customise credentials or ports, open `.env` and update the values before proceeding.

### 3. Build and Start All Services

```bash
docker compose up --build -d
```

This single command:
- Builds the Spring Boot backend JAR (multi-stage Docker build)
- Builds the Angular frontend and serves it via Nginx
- Builds the Mock Payment microservice
- Starts PostgreSQL 15, Redis 7, Keycloak 24, and all three app services

> **First run is slow (~2–4 min)** because Docker downloads base images and compiles both Java and TypeScript.

### 4. Wait for Keycloak to Become Ready

Keycloak performs an in-memory database bootstrap and realm import on first start.

```bash
# Watch logs until you see "Listening on: http://0.0.0.0:8080"
docker compose logs -f seat-reservation-keycloak
```

Press `Ctrl+C` once you see the ready message (usually 30–60 seconds).

Alternatively, poll the health endpoint:

```bash
until curl -sf http://localhost:8180/realms/seat-reservation > /dev/null; do
  echo "Waiting for Keycloak..."; sleep 3;
done
echo "Keycloak is ready"
```

### 5. Verify All Services Are Healthy

```bash
docker compose ps
```

Expected output (all `healthy` or `running`):

```
NAME                            STATUS
seat-reservation-postgres       running (healthy)
seat-reservation-redis          running (healthy)
seat-reservation-keycloak       running
seat-reservation-mock-payment   running
seat-reservation-backend        running
seat-reservation-frontend       running
```

### 6. Open the Application

Navigate to **http://localhost:4200** in your browser.

**Login credentials:**

| User | Password | Role |
|------|----------|------|
| `user@tpthinh.com` | `User1234!` | USER — can view seats and make bookings |
| `admin@tpthinh.com` | `Admin1234!` | ADMIN — can also view pending bookings, audit logs, and trigger reconciliation |

---

## Service Port Reference

| Service | URL | Description |
|---------|-----|-------------|
| Frontend (Angular) | http://localhost:4200 | Main web UI |
| Backend (Spring Boot) | http://localhost:8080 | REST API |
| Keycloak | http://localhost:8180 | Identity Provider (admin console: `/admin`) |
| PostgreSQL | localhost:5432 | Database (`seat` / `seat`) |
| Redis | localhost:6379 | Cache + distributed lock |
| Mock Payment Service | http://localhost:9090 | Simulated payment gateway |

---

## Stopping the Application

```bash
# Stop all containers (data is preserved in the postgres_data volume)
docker compose down

# Stop AND wipe all data (fresh start)
docker compose down -v
```

---

## Running Tests

> **Requires Docker to be running** — the test suite uses Testcontainers to automatically spin up ephemeral PostgreSQL and Redis instances. You do not need to manually start PostgreSQL/Redis containers via docker compose.

```bash
# Inside WSL or on the host shell
cd backend
../gradlew test
```

Or inside WSL directly:

```bash
cd ~/playground/seat-reservation/seat-reservation/backend
../gradlew test
```

Test report is generated at:
```
backend/build/reports/tests/test/index.html
```

---

## Troubleshooting

### Backend fails to start — "Connection refused to Keycloak"

The backend validates Keycloak's JWKS endpoint on startup. If Keycloak is still booting, the backend may crash. Fix:

```bash
docker compose restart backend
```

### Port already in use

```bash
# Find which process is using port 8080
lsof -i :8080
# Or on Windows
netstat -ano | findstr :8080
```

### Flyway migration errors

If the database schema is in a dirty state from a failed migration:

```bash
docker compose down -v   # wipe DB volume
docker compose up -d     # re-run migrations from scratch
```

### Viewing Backend Logs

```bash
docker compose logs -f backend
```

### Connecting to the Database Directly

```bash
docker exec -it seat-reservation-postgres psql -U seat -d seatreservation
```

---

## Environment Variables Reference

See [`.env.example`](.env.example) for the full list with descriptions.
