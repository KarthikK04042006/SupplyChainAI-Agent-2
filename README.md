# Demand Forecasting System

Production-grade demand forecasting and inventory optimization system.

## Architecture

```
┌──────────────┐     REST      ┌──────────────┐
│   backend/   │◄─────────────►│  ml-service/ │
│  Spring Boot │               │   FastAPI    │
│  Java 21     │               │  Python 3.11 │
└──────┬───────┘               └──────────────┘
       │
       │ MySQL
       │
┌──────▼───────┐
│    MySQL     │
│     8.0      │
└──────────────┘
```

### Services

| Service | Language | Framework | Port | Responsibilities |
|---------|----------|-----------|------|------------------|
| **backend** | Java 21 | Spring Boot 3.x | 8080 | Auth, persistence, business logic, ML orchestration |
| **ml-service** | Python 3.11 | FastAPI | 8000 | Forecasting, inventory optimization, analytics |
| **mysql** | — | MySQL 8.0 | 3306 | Data persistence (owned by backend) |

### Key Design Decisions

- **Stateless ML Service**: The ML service has no database access of its own. The backend passes it data in each request, and it returns predictions/scores. This keeps the ML service simple and horizontally scalable.
- **Backend Owns Data**: All persistence goes through the backend's Spring Data JPA repositories. The ML service never talks to the database directly.
- **Internal REST**: Communication between services happens over the internal Docker network (`supply-net`) at `http://ml-service:8000/...`.

## Quick Start

### Prerequisites

- Docker & Docker Compose

### Run All Services

```bash
docker-compose up --build -d
```

This starts MySQL, the ML service, and the backend. Once healthy:

```bash
# Check backend health (also pings ml-service and database)
curl http://localhost:8080/api/health
# {"backend":"UP","ml-service":"UP","database":"UP"}

# Check ML service directly
curl http://localhost:8000/health
# {"status":"UP","service":"ml-service"}
```

### Stop

```bash
docker-compose down          # keep volumes
docker-compose down -v       # remove volumes too
```

## Development

### Backend

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The `local` profile uses `localhost` for MySQL and ML service instead of Docker hostnames.

### ML Service

```bash
cd ml-service
pip install -r requirements.txt
uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

API docs: http://localhost:8000/docs (OpenAPI/Swagger generated automatically by FastAPI)

## Project Layout

```
├── docker-compose.yml
├── README.md
├── backend/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/supply/demandforecast/
│       ├── DemandForecastApplication.java
│       ├── config/       — SecurityConfig, AppConfig (RestTemplate)
│       ├── controller/   — REST controllers (HealthController)
│       ├── service/      — Business logic
│       ├── repository/   — Spring Data JPA repositories
│       ├── entity/       — JPA entities
│       ├── dto/          — Data transfer objects
│       ├── security/     — Security utilities
│       └── client/       — ML service REST client
└── ml-service/
    ├── Dockerfile
    ├── requirements.txt
    ├── main.py
    ├── core/
    │   └── config.py     — Pydantic settings
    ├── routers/
    │   └── health.py     — Health check endpoint
    ├── models/
    │   └── schemas.py    — Pydantic models
    └── services/         — Forecasting logic (placeholder)
```
