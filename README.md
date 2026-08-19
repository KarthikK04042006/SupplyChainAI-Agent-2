# Demand Forecasting System

Demand forecasting and inventory optimization: a Spring Boot backend that owns the data and a
FastAPI service that does the modelling, behind a role-gated REST API and a small dashboard.

## Architecture

```
                    ┌──────────────┐
   browser ────────►│   backend/   │◄──── REST ────►┌──────────────┐
   (dashboard)      │  Spring Boot │                │  ml-service/ │
                    │   Java 21    │                │   FastAPI    │
                    └──────┬───────┘                │  Python 3.11 │
                           │                        └──────────────┘
                           │ JPA
                    ┌──────▼───────┐
                    │    MySQL     │
                    │     8.0      │
                    └──────────────┘
```

### Request pipeline

A request crosses these in order, and the order is deliberate:

```
   request
     │
     ▼  RequestIdFilter        reads or mints X-Request-Id, binds it to the log MDC — runs first
     │                         (HIGHEST_PRECEDENCE) so even auth failures are traceable
     ▼  Spring Security        authenticates the JWT and resolves the role
     ▼  RateLimitInterceptor   token bucket per caller on uploads and scan; over budget → 429.
     │                         A HandlerInterceptor, not a filter, so it runs after auth (the
     │                         principal is the bucket key) and its 429 flows through the same
     │                         GlobalExceptionHandler as every other error
     ▼  controller → service   the work itself
     ▼  MlServiceClient        copies the id onto the X-Request-Id header of every ML call, so
                               the ml-service logs that request under the same id
```

### Services

| Service | Language | Framework | Port | Responsibilities |
|---------|----------|-----------|------|------------------|
| **backend** | Java 21 | Spring Boot 3.4 | 8080 | Auth, persistence, CSV import, scheduled scanning, alerting, ML orchestration, dashboard |
| **ml-service** | Python 3.11 | FastAPI | 8000 | Model selection, forecasting, inventory optimization, explainability |
| **mysql** | — | MySQL 8.0 | 3306 | Data persistence (owned by backend) |

### Key design decisions

- **Stateless ML service.** It has no database access. The backend ships the history it wants
  modelled in each request and gets predictions back, so the service can be scaled or restarted
  without any state to migrate.
- **Backend owns data.** All persistence goes through Spring Data JPA repositories.
- **Model choice is measured, not assumed.** The ML service backtests viable candidates and picks
  a winner on error, rather than committing to one algorithm up front.
- **Internal REST.** Service-to-service calls go over the Docker network (`supply-net`) at
  `http://ml-service:8000`.
- **One request-id, both services.** A request-id filter mints or accepts an `X-Request-Id`, binds
  it to the log MDC, echoes it on the response, and forwards it to the ML service — so a single
  call's log lines line up across both processes. Logs are structured plain text carrying it in
  `[brackets]` on every line, with no log-aggregation dependency required.
- **Tokens prove identity, not privilege.** A bearer token's signature is verified on every
  request, and then the account is re-read from the database: a disabled, deleted, or demoted
  user is refused on their next call rather than when their token happens to expire. That costs
  one indexed lookup per request and is what makes `PATCH /api/users/{id}/enabled` a revocation
  instead of a note for the next login.
- **Bounded input and request rate.** Uploads are capped by multipart size limits and a per-file
  row cap (413 past either); the CSV imports and the scan trigger sit behind a dependency-free
  in-memory token bucket (429 with `Retry-After` past the budget). Both guard against runaway loops
  and abuse rather than normal use, and the scheduled scan runs in-process so it is unaffected.

## What this adds over the original Streamlit version

The first version was a single Python script with a Streamlit UI (Pandas, NumPy, scikit-learn). It
forecast demand from historical sales, recommended reorder quantities, flagged shortage and
overstock conditions, and drew them on an interactive dashboard. It ran locally for one person, read
its data from CSVs each session, used one fixed forecasting model, and had no persistence, no
accounts, and no API.

This rebuild keeps every one of those goals and re-shapes them for more than one user and a real
deployment. The same-named capabilities differ mostly in *how*:

| Concern | Original Streamlit app | This system |
|---------|------------------------|-------------|
| Forecasting | One fixed model | Per-request selection across baseline, statistical, and ML candidates, chosen by rolling-origin backtest on error; a short series falls back to a baseline rather than failing |
| Explainability | — | SHAP per-feature contributions for a forecasted day (`/explain`), when the winning model supports it |
| Reorder advice | Computed and shown | The same maths, exposed as an endpoint and reused by a what-if panel that recomputes it under a hypothetical demand or lead-time shock, saving nothing |
| Inventory monitoring | Conditions shown in the dashboard at view time | A scheduled background scan scores risk and opens alerts as records — severity, a resolve workflow, history — not only a live readout |
| Data | CSVs loaded per session, in memory | MySQL via JPA; CSV import validates and reports per-row rejections, and what it accepts persists |
| Access | Single user, local | JWT auth with a VIEWER/MANAGER/ADMIN role hierarchy, enforced server-side on every route |
| Interface | Streamlit script | A documented, role-gated REST API with an RFC-7807 error contract; the dashboard is one client of it |
| Operability | `streamlit run` | Two services — a stateless FastAPI modeller and a Spring Boot backend that owns the data — on docker-compose, with a request-id traced across both, structured logs, rate and upload-size limits, and a one-command end-to-end smoke test |

The through-line is the same problem — forecast demand, recommend reorders, watch for stockouts and
overstock — rebuilt as a service others can call and depend on, rather than an app one analyst runs
on their own machine.

## Quick start

Requires Docker and Docker Compose.

```bash
ADMIN_PASSWORD=some-long-password docker compose up --build -d
```

Without `ADMIN_PASSWORD` the initial admin password is generated on first boot and written to the
backend log exactly once. Pin it if you want repeatable runs.

```bash
# Backend health — also pings ml-service and the database
curl http://localhost:8080/api/health
# {"backend":"UP","ml-service":"UP","database":"UP"}

curl http://localhost:8000/health
# {"status":"UP","service":"ml-service"}
```

Then open http://localhost:8080/ and sign in.

```bash
docker compose down          # keep volumes
docker compose down -v       # drop the database too
```

## Backend API

All routes require a bearer token from `POST /api/auth/login` except health and login itself.
Roles are hierarchical — `ADMIN` inherits `MANAGER`, which inherits `VIEWER` — declared as a
`RoleHierarchy` bean in `SecurityConfig`, which is also where every rule below is enforced.
Failures are translated to problem responses by `GlobalExceptionHandler`.

| Method | Path | Role | Purpose |
|--------|------|------|---------|
| GET | `/api/health` | — | Backend, ML service, and database liveness |
| POST | `/api/auth/login` | — | Exchange credentials for a JWT |
| GET | `/api/auth/me` | VIEWER | Current user and role |
| GET | `/api/dashboard/summary` | VIEWER | Headline counts and KPIs for the overview page |
| GET | `/api/products` | VIEWER | Paged product list |
| GET | `/api/products/{id}` | VIEWER | One product |
| GET | `/api/products/{id}/demand-history` | VIEWER | Observed demand series |
| GET | `/api/products/{id}/forecast` | VIEWER | Forecast for one product |
| GET | `/api/products/{id}/reorder-recommendation` | VIEWER | Safety stock, reorder point, order quantity |
| GET | `/api/products/{id}/explain` | VIEWER | Per-feature contributions for a forecasted day |
| POST | `/api/forecast/{sku}` | VIEWER | Ad-hoc forecast; body optional, defaults applied when omitted |
| POST | `/api/simulate` | VIEWER | What-if: recompute a reorder recommendation under a demand or lead-time shock, persisting nothing |
| GET | `/api/inventory/levels` | VIEWER | Stock levels per product and warehouse |
| GET | `/api/inventory/scan` | VIEWER | Result of the most recent risk scan |
| GET | `/api/alerts` | VIEWER | Paged alerts, filterable |
| GET | `/api/alerts/{id}` | VIEWER | One alert |
| POST | `/api/inventory/scan` | MANAGER | Run a risk scan now |
| PATCH | `/api/alerts/{id}/resolve` | MANAGER | Resolve an open alert |
| POST | `/api/products/import` | ADMIN | Import products CSV |
| POST | `/api/inventory/import` | ADMIN | Import inventory levels CSV |
| POST | `/api/demand/import` | ADMIN | Import demand history CSV |
| GET | `/api/users` | ADMIN | List users |
| POST | `/api/users` | ADMIN | Create a user |
| PATCH | `/api/users/{id}/role` | ADMIN | Change a user's role |
| PATCH | `/api/users/{id}/enabled` | ADMIN | Enable or disable an account |

Anything under `/api/**` not matched above is denied rather than left to a default.

### Background work

`ScheduledInventoryScan` re-runs the risk scan on a `fixedDelay` of `inventory.scan.interval`
(default `1h`), measured from the end of the previous run so a slow scan cannot overlap itself.
Newly detected stockout and overstock risks are raised as alerts.

## ML service API

OpenAPI docs are generated at http://localhost:8000/docs.

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/health` | Liveness |
| POST | `/forecast` | Forecast one SKU |
| POST | `/forecast/batch` | Forecast many SKUs in one call, reporting per-SKU failures |
| POST | `/inventory/reorder-recommendation` | Safety stock, reorder point, order quantity |
| POST | `/inventory/risk-scan` | Classify SKUs as OK, stockout risk, or overstock |
| POST | `/explain/{product_id}` | Decompose a forecasted day into per-feature SHAP contributions |

### How a forecast is chosen

For each request the service builds the candidate models that are viable for the series' length
and seasonality, backtests each over rolling folds, and selects the lowest error — MAPE where it
is meaningful, RMSE otherwise. Candidates:

| Model | Kind | Notes |
|-------|------|-------|
| `MovingAverageModel` | baseline | Always viable; the fallback when nothing else fits |
| `SeasonalNaiveModel` | baseline | Repeats the last seasonal cycle |
| `HoltWintersModel` | statistical | Exponential smoothing with trend and seasonality |
| `SarimaModel` | statistical | Seasonal ARIMA |
| `ProphetModel` | statistical | Optional — skipped when Prophet is not importable |
| `XGBoostModel` | ML | Gradient boosting on engineered features; required for `/explain` |

Expensive candidates are gated behind their own `min_observations`, and a short or degenerate
series falls back to the baseline rather than failing the request. Because model choice is
per-request, `/explain` answers 422 when the winner was not XGBoost — SHAP contributions would
not describe the model that actually produced the numbers. Forecasts also carry warnings when the
history behind them is distorted, e.g. by a detected spike.

Supporting modules: `features` (calendar and lag features), `seasonality` (cycle detection),
`anomalies` (spike and drop detection), `backtest` (rolling-origin folds), `batch`, `explain`.

## Dashboard

Five pages, served as static resources from the backend and driven by the API above. No build
step or framework — plain ES modules, with Bootstrap and Chart.js vendored into `static/vendor/`.

| Page | Role | Contents |
|------|------|----------|
| `login.html` | — | Sign in; stores the JWT in `sessionStorage` |
| `overview.html` | VIEWER | Four KPI tiles, filterable and paged open-alerts table, manual scan |
| `product.html` | VIEWER | KPI tiles, history-and-forecast chart, reorder advice, explanation, model selection |
| `inventory.html` | VIEWER | Stock levels by warehouse with risk status |
| `upload.html` | ADMIN | CSV upload for all three feeds, with per-row rejection reporting |

`index.html` is a redirect shim, not a page: it sends signed-in users to `overview.html` and
everyone else to `login.html`. Because the decision reads `sessionStorage`, it has to happen in
the browser.

Role gating is enforced server-side. The UI hides what a role cannot use, but the API is what
actually refuses.

## Development

### Backend

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The `local` profile points at `localhost` for MySQL and the ML service instead of Docker
hostnames. With no MySQL installed, `local-h2` swaps in an in-memory H2 database in MySQL
compatibility mode — H2 is test-scoped, so it needs the test classpath:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local-h2 \
    -Dspring-boot.run.useTestClasspath=true
```

That profile is for exercising the API and dashboard, not a substitute for `local`: data lives
only as long as the process, and dialect-sensitive work is not covered.

### ML service

```bash
cd ml-service
pip install -r requirements.txt
uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

### Tests

```bash
cd backend && ./mvnw test        # 169 tests
cd ml-service && pytest tests    # 151 tests, 7 skipped
```

Backend coverage spans the API surface, role gating, CSV import, repositories, ML orchestration
against a stubbed service, the ML client's own status and retry handling against stubbed HTTP
(`MlServiceClientTest`), a full import-to-alert loop (`FullLoopTest`), and a contract test
pinning the ML request and response shapes (`MlContractTest`). The ML suite covers models,
selection, backtesting, seasonality, anomalies, batching, inventory maths, and explainability.
Skips are conditional on optional dependencies such as Prophet.

If the JVM dies with an mmap or "insufficient memory" error, it is the host and not the suite —
cap the heap with `MAVEN_OPTS="-Xmx768m" ./mvnw test -DargLine="-Xmx512m"`.

### End-to-end verification

```bash
ADMIN_PASSWORD=some-long-password docker compose up --build -d
scripts/verify-dashboard.sh
```

Loads the sample CSVs, runs a scan, reads back every API the pages call and asserts the data is
real rather than merely well-formed, re-checks role gating over HTTP, and confirms a malformed CSV
reports its rejected rows. It does not look at a rendered page: layout, charts, and responsive
behaviour still need a browser. Exits non-zero if any check fails.

For a one-command check that owns the whole stack — build, up, seed, assert, tear down —
`scripts/smoke-test.sh` brings up docker-compose from a clean volume, waits for health, imports the
sample data, runs a scan, and asserts open alerts exist and that the forecast and what-if endpoints
return the right shape. Unlike `verify-dashboard.sh` it starts and stops the stack itself, so it
suits a CI gate or a pre-release smoke test.

## Project layout

```
├── docker-compose.yml
├── sample-data/            — products, inventory levels, demand history CSVs
├── scripts/
│   ├── smoke-test.sh       — one-command end-to-end regression check; owns the compose stack
│   ├── verify-dashboard.sh — end-to-end check against a running stack
│   ├── mysql-local-setup.sql   — creates the DB and user the `local` profile expects
│   └── install-docker-to-E.ps1 — Windows helper: installs Docker Desktop off the C: drive
├── backend/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/supply/demandforecast/
│       │   ├── client/     — ML service client, bounded retries, error translation
│       │   ├── config/     — security, role hierarchy, scheduling, admin seeding
│       │   ├── controller/ — 10 REST controllers + GlobalExceptionHandler
│       │   ├── domain/     — Role, AlertType, Severity, RiskStatus
│       │   ├── dto/        — request/response records
│       │   ├── entity/     — Product, Warehouse, DemandHistory, InventoryLevel,
│       │   │                 Alert, ForecastAccuracy, User
│       │   ├── logging/    — request-id filter and the ML-client header interceptor
│       │   ├── ratelimit/  — token bucket, its interceptor, and settings
│       │   ├── repository/ — Spring Data JPA repositories
│       │   ├── security/   — JWT filter, token service, user details
│       │   └── service/    — forecasting, replenishment, scanning, alerts,
│       │                     CSV import, users, dashboard
│       ├── main/resources/
│       │   ├── application.yml, application-local-h2.yml, logback-spring.xml
│       │   └── static/     — dashboard pages, js/, css/, vendored bootstrap + chart.js
│       └── test/           — 21 test classes
└── ml-service/
    ├── Dockerfile
    ├── requirements.txt
    ├── main.py
    ├── core/config.py      — Pydantic settings
    ├── core/observability.py — request-id middleware and log configuration
    ├── models/schemas.py   — request/response models
    ├── routers/            — health, forecast, inventory, explain
    ├── services/
    │   ├── models/         — baseline, statistical, and ML forecasters
    │   └── ...             — features, seasonality, anomalies, backtest,
    │                         batch, explain, forecasting, inventory
    └── tests/              — 8 test modules
```
