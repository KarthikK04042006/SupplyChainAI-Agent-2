# ⚡ SupplyChainAI Agent (v2)
### Distributed Demand Forecasting & Inventory Optimization Engine

[![Java 21](https://img.shields.io/badge/Java-21-orange.svg?style=flat-square&logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.4.1-brightgreen.svg?style=flat-square&logo=springboot)](https://spring.io/projects/spring-boot)
[![Python](https://img.shields.io/badge/Python-3.11-blue.svg?style=flat-square&logo=python)](https://www.python.org/)
[![FastAPI](https://img.shields.io/badge/FastAPI-0.115-009688.svg?style=flat-square&logo=fastapi)](https://fastapi.tiangolo.com/)
[![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1.svg?style=flat-square&logo=mysql)](https://www.mysql.com/)
[![Docker](https://img.shields.io/badge/Docker_Compose-Enabled-2496ED.svg?style=flat-square&logo=docker)](https://www.docker.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square)](LICENSE)

> **SupplyChainAI Agent v2** is an enterprise-scale distributed platform designed to eliminate stockouts and overstock conditions. It decouples core business logic, persistence, and role-gated REST APIs (**Spring Boot 3.4**) from high-performance mathematical modeling, automated model selection, and SHAP explainability (**FastAPI / Python 3.11**).

*Looking for the v1 monolithic prototype? Check out [SupplyChainAI-Agent-1](https://github.com/KarthikK04042006/SupplyChainAI-Agent-1).*

---

## 🏛️ System Architecture

```
                    ┌──────────────────────────┐
   Browser ────────►│        backend/          │◄──── Internal REST ───►┌──────────────────────────┐
 (Dashboard)        │     Spring Boot 3.4      │     (supply-net:8000)  │       ml-service/        │
                    │         Java 21          │                        │     FastAPI / Uvicorn    │
                    └────────────┬─────────────┘                        │       Python 3.11        │
                                 │                                      └──────────────────────────┘
                                 │ JPA / Hibernate
                    ┌────────────▼─────────────┐
                    │          MySQL           │
                    │           8.0            │
                    └──────────────────────────┘
```

### Microservices Responsibilities

| Service | Technology | Port | Responsibilities |
| :--- | :--- | :--- | :--- |
| **`backend`** | Java 21, Spring Boot 3.4.1, JPA, Security | `8080` | Data persistence, role-gated REST API, CSV streaming ingestion, scheduled risk scanner, alert workflows, in-memory rate limiting, and static UI delivery. |
| **`ml-service`** | Python 3.11, FastAPI, XGBoost, Statsmodels | `8000` | **Stateless computation**: Rolling-origin walk-forward model selection, point & interval forecasting, stochastic inventory calculus (Safety Stock, ROP, EOQ), and SHAP attribution. |
| **`mysql`** | MySQL 8.0 Database | `3306` | Persistent relational storage for catalog products, demand history, warehouses, inventory levels, alerts, and forecast accuracy metrics. |

---

## 🚀 Key Innovations & Engineering Highlights

### 1. Empirical Walk-Forward Model Selection
Rather than assuming a static algorithm fits every SKU, the ML service tests viable candidates using rolling-origin cross-validation folds and selects the lowest-error model (**MAPE / RMSE**):
* **Baselines**: `MovingAverageModel` (robust fallback), `SeasonalNaiveModel`
* **Statistical**: `HoltWintersModel` (triple exponential smoothing), `SarimaModel`, `ProphetModel` (optional)
* **Machine Learning**: `XGBoostModel` (gradient boosted regression on calendar, lag, and rolling statistics)
* *Sparse History Fallback*: Automatically degrades to moving-average baselines if observations $< 21$ without failing requests.

### 2. Explainable AI with Tree SHAP
When `XGBoost` produces the winning forecast, `/api/products/{id}/explain` decomposes the prediction into exact feature attributions—quantifying the demand impact of calendar seasonality, day-of-week cycles, and rolling trends.

### 3. Stochastic Inventory Replenishment Calculus
* **Safety Stock (SS)**: Sized using both demand variance and supplier lead-time variance:
  $$\text{SS} = z \times \sqrt{L \cdot \sigma_D^2 + \bar{D}^2 \cdot \sigma_L^2}$$
* **Reorder Point (ROP)**: Covers protection period demand (lead time + review period) plus safety stock.
* **Economic Order Quantity (EOQ)**: Automatically balances annual order placement fees against inventory holding costs.
* **What-If Simulation (`/api/simulate`)**: Allows operators to test hypothetical shocks to lead time or demand without persisting state.

### 4. Defense-in-Depth Security & Traceability
* **Stateless JWT with Database Verification**: Tokens prove identity; database lookup verifies active account state on each request. Disabling an account immediately revokes access.
* **Hierarchical RBAC**: `ADMIN` $\supset$ `MANAGER` $\supset$ `VIEWER`.
* **Distributed Request Tracing**: Unified `X-Request-Id` binds to Spring MDC, passes through HTTP headers, and propagates to FastAPI middleware for unified log aggregation.
* **Token-Bucket Rate Limiting**: In-memory token bucket guards compute-heavy scan and upload endpoints (429 with `Retry-After`).

---

## 📂 Repository Layout

```
├── docker-compose.yml          # Container orchestration (MySQL, FastAPI, Spring Boot)
├── sample-data/                # Sample CSV data (products, demand history, inventory levels)
├── scripts/
│   ├── smoke-test.sh           # Clean-slate spin up, seed, assert, and tear down script
│   ├── verify-dashboard.sh     # End-to-end integration test against a running stack
│   ├── verify_live.py          # Python live endpoint and math validation script
│   └── mysql-local-setup.sql   # Local database provisioning script
├── backend/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/supply/demandforecast/
│       │   ├── client/         # MlServiceClient with bounded retries and timeouts
│       │   ├── config/         # SecurityConfig, AdminSeeder, SchedulingConfig, WebConfig
│       │   ├── controller/     # 10 REST controllers (Alert, Auth, Forecast, Inventory, etc.)
│       │   ├── domain/         # Enums (Role, AlertType, Severity, RiskStatus)
│       │   ├── dto/            # Request/Response records
│       │   ├── entity/         # JPA Entities (Product, DemandHistory, InventoryLevel, Alert, User)
│       │   ├── logging/        # RequestIdFilter and log interceptors
│       │   ├── ratelimit/      # TokenBucketRateLimiter and HandlerInterceptor
│       │   ├── repository/     # Spring Data JPA repositories
│       │   ├── security/       # JwtService, JwtAuthenticationFilter, UserDetailsService
│       │   └── service/        # ForecastService, ReplenishmentService, InventoryScanService
│       └── resources/
│           ├── application.yml # Multi-profile configuration (default, local, local-h2)
│           └── static/         # Vanilla ES Module dashboard (overview, product, inventory, upload)
└── ml-service/
    ├── Dockerfile
    ├── requirements.txt
    ├── main.py                 # FastAPI application setup and middleware registration
    ├── core/                   # Pydantic configuration and observability logging/tracing
    ├── models/schemas.py       # Pydantic schemas for requests/responses
    ├── routers/                # Endpoints (/health, /forecast, /inventory, /explain)
    ├── services/               # Core analytics: anomalies, backtest, features, seasonality
    │   └── models/             # Baseline, Statistical (HW, SARIMA, Prophet), ML (XGBoost)
    └── tests/                  # Pytest test suite covering models, metrics, and endpoints
```

---

## ⚡ Quickstart Guide

### Option 1: Docker Compose (Recommended)

```bash
# Clone the repository
git clone https://github.com/KarthikK04042006/SupplyChainAI-Agent-2.git
cd SupplyChainAI-Agent-2

# Launch all 3 services
ADMIN_PASSWORD=admin123 docker compose up --build -d
```

### Option 2: Local Development (Without Docker)

1. **Start the ML Service**:
   ```bash
   cd ml-service
   python -m venv .venv
   .\.venv\Scripts\activate      # On Windows (or source .venv/bin/activate on Linux/Mac)
   pip install -r requirements.txt
   uvicorn main:app --host 0.0.0.0 --port 8000 --reload
   ```

2. **Start the Backend Service** (Uses in-memory H2 database in MySQL mode):
   ```bash
   cd backend
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=local-h2 -Dspring-boot.run.useTestClasspath=true
   ```

---

## 🌐 Endpoints & Web Dashboard

| Interface | URL | Description |
| :--- | :--- | :--- |
| **Web Dashboard** | [http://localhost:8080/](http://localhost:8080/) | Visual SPA dashboard (Overview, Alerts, Products, Inventory, CSV Upload) |
| **FastAPI Swagger Docs** | [http://localhost:8000/docs](http://localhost:8000/docs) | Interactive API explorer for ML endpoints |
| **Health Check** | [http://localhost:8080/api/health](http://localhost:8080/api/health) | Real-time health status of Backend, ML Service, and Database |

### Default Credentials
* **Username**: `admin`
* **Password**: `admin` (or whatever `ADMIN_PASSWORD` was set to)

---

## 🧪 Testing & Verification

Run the full automated test suites:

```bash
# Run Backend Tests (181 test cases)
cd backend && ./mvnw test

# Run ML Service Tests (151 unit & property tests)
cd ml-service && pytest tests

# Run End-to-End System Smoke Test
./scripts/smoke-test.sh
```

---

## 📄 License
This project is licensed under the [MIT License](LICENSE).
