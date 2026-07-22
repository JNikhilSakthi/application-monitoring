# Application Monitoring Dashboard

Turn a Spring Boot app into a fully observable system: custom Micrometer metrics, scraped by Prometheus, visualized live in a pre-provisioned Grafana dashboard.

![Java 25](https://img.shields.io/badge/Java-25-orange)
![Spring Boot 4.0.6](https://img.shields.io/badge/Spring%20Boot-4.0.6-brightgreen)
![Micrometer](https://img.shields.io/badge/Micrometer-Prometheus-red)
![Prometheus](https://img.shields.io/badge/Prometheus-v2.54.1-e6522c)
![Grafana](https://img.shields.io/badge/Grafana-11.2.0-f46800)
![MySQL 8](https://img.shields.io/badge/MySQL-8.0-4479A1)
![Flyway](https://img.shields.io/badge/Flyway-migrations-CC0200)
![Docker Compose](https://img.shields.io/badge/Docker%20Compose-ready-2496ED)
![License MIT](https://img.shields.io/badge/License-MIT-blue)

**Learning Track:** `springboot-monitoring-demo` (Project 9 of 17)
**Real-World Service Name:** `application-monitoring`

---

## 1. Project Overview

Every production Spring Boot service eventually gets asked the same three questions: *is it up, is it fast, and is it failing?* Answering them well requires more than logs — it requires **metrics**: numeric time series that can be aggregated, alerted on, and graphed over time. This project builds the observability stack that answers those questions, using a small **Order Processing REST API** purely as a realistic traffic generator. The API itself (create/get/list/updateStatus/cancel orders) is intentionally simple — the point of this project is not the domain, it's everything wrapped around it:

- **Micrometer** as the vendor-neutral metrics facade baked into Spring Boot Actuator.
- **Custom business metrics** (counters, a distribution summary, a timer, a gauge) registered directly against a `MeterRegistry`, in addition to the framework's own HTTP/JVM metrics.
- **Prometheus** scraping `/actuator/prometheus` every 5 seconds and storing the resulting time series.
- **Grafana**, auto-provisioned with a Prometheus datasource and a ready-made dashboard, rendering those time series as live graphs.
- A **`TrafficSimulator`** that continuously creates and progresses orders so the dashboard is never a flat line — `docker-compose up` alone produces a moving, realistic monitoring picture.

**Why Prometheus + Grafana specifically?** It's the de-facto open-source standard for metrics-based observability: a pull-based model (Prometheus scrapes targets, rather than services pushing to a collector) that scales horizontally and degrades gracefully if a scrape is missed; a powerful query language (PromQL) for rates, percentiles, and aggregations; and Grafana as the near-universal visualization layer on top of it, alerting layer included. This combination is used by companies of every size — Netflix, Uber-scale platform teams, and equally by small SaaS shops — because it's free, self-hostable, Kubernetes-native (via the Prometheus Operator / kube-prometheus-stack), and every major cloud (AWS Managed Prometheus, Google Managed Prometheus, Azure Monitor) now offers it as a managed service, so the skills transfer directly.

**Where this pattern shows up in real companies:**
- SRE/platform teams building **golden signals** dashboards (latency, traffic, errors, saturation) for every microservice.
- **Capacity planning** — JVM heap/GC and CPU panels here are the same panels used to decide when to scale a pod or bump `-Xmx`.
- **SLO/error-budget tracking** — the `http_server_requests_seconds` histogram with SLO buckets configured below is exactly what backs Prometheus-based SLO alerting (e.g., burn-rate alerts).
- **Business KPI observability** — `orders_creation_total`, `orders_failed_total`, `order_value_amount_currency` are stand-ins for real business counters (payments processed, signups, checkout failures) that product and on-call engineers both watch.

---

## 2. Architecture

### High-Level Design (HLD)

```
                         ┌────────────────────────────┐
                         │        Client / curl        │
                         └──────────────┬──────────────┘
                                         │ HTTP (REST JSON)
                                         ▼
┌───────────────────────────────────────────────────────────────────────┐
│                    springboot-monitoring-demo (app)                    │
│                                                                         │
│   OrderController → OrderService → OrderRepository → MySQL (orders)   │
│         │                  │                                          │
│         │                  ├─ Counters / Summary / Timer / Gauge      │
│         │                  │  registered on MeterRegistry             │
│         │                  │                                          │
│   TrafficSimulator  ───────┘ (scheduled, generates synthetic load)     │
│                                                                         │
│   Actuator: /actuator/health, /actuator/metrics, /actuator/prometheus  │
└───────────────────────────────────┬───────────────────────────────────┘
                                     │ scrape every 5s (GET /actuator/prometheus)
                                     ▼
                         ┌────────────────────────────┐
                         │          Prometheus         │
                         │  (time-series storage +     │
                         │   PromQL query engine)      │
                         └──────────────┬──────────────┘
                                         │ PromQL queries
                                         ▼
                         ┌────────────────────────────┐
                         │           Grafana            │
                         │  Prometheus datasource +     │
                         │  "Application Monitoring     │
                         │   Dashboard" (auto-provision) │
                         └────────────────────────────┘
```

### Low-Level Design (LLD) — request & metrics flow

```
POST /api/orders
   │
   ▼
OrderController.createOrder(@Valid OrderRequest)
   │
   ▼
OrderService.createOrder()                       @Timed("order_service_create")
   │  1. compute totalAmount = unitPrice * quantity
   │  2. build Order (status = PENDING), save via OrderRepository (JPA/Hibernate → MySQL)
   │  3. ordersCreatedCounter.increment()          → orders_creation_total
   │  4. orderValueSummary.record(totalAmount)     → order_value_amount_currency{,_sum,_count,_bucket}
   ▼
OrderResponse (201 Created)

PATCH /api/orders/{id}/status
   │
   ▼
OrderService.updateStatus()                       @Timed("order_service_update_status")
   │  1. load Order, validate transition against ALLOWED_TRANSITIONS state machine
   │  2. Timer.Sample around the persistence step   → order_processing_time_seconds
   │  3. if FAILED   → ordersFailedCounter++         → orders_failed_total
   │     if CANCELLED→ ordersCancelledCounter++      → orders_cancelled_total
   ▼
OrderResponse (200) / 409 Conflict (invalid transition) / 404 (not found)

orders_active gauge: NOT an in-memory counter — registered once via
   meterRegistry.gauge("orders_active", this, OrderService::countActiveOrders)
which re-queries `count(status=PENDING) + count(status=PROCESSING)` from MySQL
on every Prometheus scrape, so it is always correct even after an app restart
or if multiple instances shared the same database.
```

> **Naming note:** the order-creation counter is registered as `orders_creation_total`, not
> `orders_created_total`. On Spring Boot 4 / Micrometer 1.16 (bundled Prometheus Java client
> 1.4.x), a literal `_created` token inside a counter name collides with the reserved OpenMetrics
> "created-timestamp" suffix and gets silently stripped at scrape time (`orders_created_total`
> would render as `orders_total` on `/actuator/prometheus`). Renaming avoids the collision; see
> the Tech Stack / migration notes below for the full breaking-change list.

### Folder structure

```
springboot-monitoring-demo/
├── pom.xml
├── Dockerfile
├── docker-compose.yml
├── .dockerignore / .gitignore
├── prometheus/
│   └── prometheus.yml                     # scrape config
├── grafana/
│   ├── provisioning/
│   │   ├── datasources/datasource.yml     # auto-wires Prometheus datasource
│   │   └── dashboards/dashboard-provider.yml
│   └── dashboards/
│       └── application-monitoring-dashboard.json
└── src/
    ├── main/java/com/medha/applicationmonitoring/
    │   ├── ApplicationMonitoringDemoApplication.java
    │   ├── config/MetricsConfig.java       # TimedAspect + common-tag customizer
    │   ├── domain/Order.java, OrderStatus.java
    │   ├── repository/OrderRepository.java
    │   ├── dto/ (OrderRequest, OrderResponse, OrderStatusUpdateRequest, PageResponse)
    │   ├── exception/ (OrderNotFoundException, InvalidOrderStateTransitionException)
    │   ├── service/OrderService.java       # business logic + all custom metrics
    │   ├── simulation/TrafficSimulator.java
    │   └── web/ (OrderController, ApiError, GlobalExceptionHandler)
    ├── main/resources/
    │   ├── application.yml
    │   └── db/migration/V1__init_schema.sql
    └── test/java/com/medha/applicationmonitoring/
        ├── service/OrderServiceTest.java
        ├── web/OrderControllerTest.java
        └── OrderApiIntegrationTest.java    # Testcontainers, full stack
```

### Database design

Single table, `orders`, created by Flyway migration `V1__init_schema.sql`:

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGINT AUTO_INCREMENT PK` | Surrogate key |
| `customer_name` | `VARCHAR(120)` | |
| `product` | `VARCHAR(120)` | |
| `quantity` | `INT` | |
| `unit_price` | `DECIMAL(12,2)` | |
| `total_amount` | `DECIMAL(14,2)` | `unit_price * quantity`, computed in the service layer |
| `status` | `VARCHAR(20)` | Enum name: `PENDING/PROCESSING/COMPLETED/FAILED/CANCELLED` |
| `created_at` / `updated_at` | `TIMESTAMP(6)` | Set via `@PrePersist`/`@PreUpdate` on the entity |

Indexes: `idx_orders_status` (used by the `orders_active` gauge query and the simulator's status filters) and `idx_orders_created_at`.

---

## 3. Tech Stack

| Layer | Technology | Why |
|---|---|---|
| Language / runtime | Java 25, Spring Boot 4.0.6 | Latest Boot generation on the latest JDK |
| Web | Spring Web (MVC), Bean Validation | REST API + request validation |
| Persistence | Spring Data JPA / Hibernate, MySQL 8 | Relational storage for orders |
| Schema migration | Flyway (`spring-boot-starter-flyway` + `flyway-mysql`) | Versioned, repeatable schema changes. Spring Boot 4 modularized Flyway autoconfiguration out of the core starter, so it now needs its own starter rather than a bare `flyway-core` dependency |
| Metrics facade | Micrometer 1.16 (`micrometer-registry-prometheus`) | Vendor-neutral metrics API, Prometheus wire format |
| AOP | `spring-boot-starter-aspectj` + `TimedAspect` | Activates `@Timed`. Spring Boot 4 renamed/replaced the old `spring-boot-starter-aop` with `spring-boot-starter-aspectj` |
| Metrics storage | Prometheus v2.54.1 | Pull-based scraping + PromQL |
| Visualization | Grafana 11.2.0 | Dashboards, auto-provisioned |
| Containerization | Docker, multi-stage `Dockerfile` (`eclipse-temurin:25-jre-jammy`) | Reproducible build & run |
| Orchestration (local) | Docker Compose | Wires app + MySQL + Prometheus + Grafana |
| Testing | JUnit 5, Mockito, AssertJ, MockMvc (`@MockitoBean`), Testcontainers (MySQL) | Unit, slice, and full-stack integration tests |
| Build | Maven (`spring-boot-starter-parent`) | Dependency + plugin management |
| Boilerplate reduction | Lombok 1.18.44 | `@Getter/@Setter/@Builder/@Slf4j` on entity/service. This version is required for correct annotation processing on JDK 25 — see the migration notes below |

**Migrating from Spring Boot 3.3.4 / Java 21 → Spring Boot 4.0.6 / Java 25 — what actually broke:**
- **Lombok annotation processing silently no-ops on JDK 25** unless the Maven Compiler Plugin is given an explicit `annotationProcessorPaths` entry for Lombok; relying on classpath auto-discovery (the old, implicit setup) compiles clean but every `@Getter`/`@Builder`/`@Slf4j` member simply doesn't exist at runtime. Fixed by pinning `lombok.version` to `1.18.44` **and** adding `annotationProcessorPaths` to the `maven-compiler-plugin` configuration.
- **`spring-boot-starter-aop` was removed** from the Spring Boot 4 BOM; replaced with `spring-boot-starter-aspectj`.
- **`@MockBean`/`@SpyBean` were removed**; replaced with `@MockitoBean`/`@MockitoSpyBean` from `org.springframework.test.context.bean.override.mockito`.
- **`@WebMvcTest`/`@AutoConfigureMockMvc` moved** out of `spring-boot-starter-test` into a new `spring-boot-starter-webmvc-test` starter, under a new package (`org.springframework.boot.webmvc.test.autoconfigure`).
- **`MeterRegistryCustomizer` moved** from `org.springframework.boot.actuate.autoconfigure.metrics` to `org.springframework.boot.micrometer.metrics.autoconfigure`.
- **Flyway autoconfiguration moved out of core** into its own module; a bare `flyway-core`/`flyway-mysql` dependency (with no `spring-boot-starter-flyway`) compiles fine but Flyway silently never runs at startup, causing Hibernate schema validation to fail with `missing table [orders]`.
- **Jackson 3 (`tools.jackson`) is now the default JSON library**; Spring Boot auto-configures a `tools.jackson.databind.json.JsonMapper` bean, not a classic `com.fasterxml.jackson.databind.ObjectMapper`. Test classes that `@Autowired` an `ObjectMapper` must import `tools.jackson.databind.ObjectMapper` (or set `spring.jackson.use-jackson2-defaults=true` to keep Jackson 2 semantics).
- **Micrometer 1.16 bundles Prometheus Java client 1.4.x**, whose stricter OpenMetrics-aware naming convention reserves the `_created` token inside counter names (see the LLD naming note above) — the app's `orders_created_total` counter was renamed to `orders_creation_total` to avoid it silently rendering as `orders_total` on scrape.

All of the above were found by actually compiling, unit-testing, and running the app end-to-end (against a real MySQL instance, scraping `/actuator/prometheus`) on JDK 25 — not by inspection alone.

---

## 4. Configuration Explained (`src/main/resources/application.yml`)

```yaml
spring:
  application:
    name: springboot-monitoring-demo
```
Names the app; reused below as the `management.metrics.tags.application` value and as the Prometheus job label — so every metric this instance emits carries a consistent identity.

```yaml
  datasource:
    url: jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME:monitoring_demo}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
    username: ${DB_USERNAME:monitoring_user}
    password: ${DB_PASSWORD:monitoring_pass}
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      pool-name: monitoring-hikari-pool
      maximum-pool-size: 10
      register-mbeans: true
```
- All connection values are environment-variable-driven with local-friendly defaults, so the same jar runs unmodified in `docker-compose` (where `DB_HOST=mysql`) or bare-metal (`localhost`).
- `useSSL=false` / `allowPublicKeyRetrieval=true` — simplify local/demo TLS handshake; not appropriate as-is for production (see Interview Prep).
- `register-mbeans: true` — exposes HikariCP pool statistics (active/idle connections, wait time) as JMX MBeans, which Micrometer's Hikari metrics binder also surfaces as `hikaricp_connections_*` Prometheus metrics — useful for spotting connection-pool exhaustion on the dashboard.

```yaml
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
```
- `ddl-auto: validate` — Hibernate never creates/alters the schema; **Flyway is the single source of truth** for schema, Hibernate only validates the entity mapping matches it. This is the correct production pattern (no silent schema drift).
- `open-in-view: false` — disables the Open Session in View anti-pattern, forcing all lazy-loading to happen inside the service/transaction boundary rather than leaking into the web layer.

```yaml
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
```
Points Flyway at `V1__init_schema.sql`; `baseline-on-migrate` lets Flyway attach to a pre-existing, non-empty database without failing (handy for demo restarts). Requires `spring-boot-starter-flyway` on the classpath on Spring Boot 4 (see Tech Stack migration notes) — with only `flyway-core`/`flyway-mysql`, this block is silently ignored and no migration ever runs.

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus, metrics
```
Only these four Actuator endpoints are exposed over HTTP — by default Boot exposes almost nothing except `health`; this explicitly opts in `prometheus` (the scrape target) and `metrics` (ad-hoc inspection) without exposing sensitive endpoints like `env`, `heapdump`, or `beans`.

```yaml
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
```
`show-details: always` surfaces the DB/disk-space health components in the JSON response (useful when debugging locally); `probes.enabled` adds Kubernetes-style `/actuator/health/liveness` and `/readiness` groups. (Spring Boot 4 enables health probes by default; this project keeps the property explicit for clarity across versions.)

```yaml
  metrics:
    tags:
      application: ${spring.application.name}
    distribution:
      percentiles-histogram:
        http.server.requests: true
        order.processing.time.seconds: true
      slo:
        http.server.requests: 50ms,100ms,200ms,500ms,1s
```
- `tags.application` — a second, config-level way every metric gets stamped with the app name (belt-and-suspenders alongside the `MeterRegistryCustomizer` bean below).
- `percentiles-histogram` on `http.server.requests` and `order.processing.time.seconds` — tells Micrometer to publish **`_bucket`** series (a real Prometheus histogram), which is what makes `histogram_quantile()` queries in the Grafana dashboard (p50/p95/p99 panels) possible. Without this, Micrometer would only expose count/sum, no percentiles.
- `slo` on `http.server.requests` — adds fixed-boundary buckets at 50/100/200/500ms/1s, giving cheap, pre-aggregated buckets for common SLO thresholds in addition to the percentile histogram.

```yaml
  prometheus:
    metrics:
      export:
        enabled: true
```
Turns on the Prometheus registry's export (i.e., actually populates `/actuator/prometheus`); paired with `micrometer-registry-prometheus` on the classpath. This property path (`management.prometheus.metrics.export.*`, not `management.metrics.export.prometheus.*`) is unchanged between Spring Boot 3.3 and 4.0.

```yaml
monitoring:
  simulation:
    enabled: ${SIMULATION_ENABLED:true}
    create-interval-ms: 1500
    progress-interval-ms: 2500
```
Custom, app-specific properties (not a Spring namespace) consumed by `TrafficSimulator` via `@Value`. `enabled` defaults to `true` for local/demo runs, is overridden to `false` in `src/test/resources/application-test.yml` so tests get a deterministic dataset, and is set explicitly via the `SIMULATION_ENABLED` environment variable in `docker-compose.yml`.

---

## 5. Project Structure Explained

| Path | Purpose |
|---|---|
| `pom.xml` | Maven build: Spring Boot 4.0.6 parent, Java 25, Web/JPA/Validation/Actuator/AspectJ starters, `micrometer-registry-prometheus`, MySQL driver, `spring-boot-starter-flyway` (+ `flyway-mysql` dialect), Lombok 1.18.44 (with explicit `annotationProcessorPaths`), and test deps (JUnit/Mockito/`spring-boot-starter-webmvc-test`/Testcontainers). Fixed `finalName` (`springboot-monitoring-demo.jar`) so the Dockerfile's `COPY` path is stable across versions. |
| `Dockerfile` | Multi-stage build: stage 1 builds the jar inside `maven:3.9-eclipse-temurin-25`; stage 2 copies only the jar into a slim `eclipse-temurin:25-jre-jammy` runtime image, running as a non-root `spring` user. |
| `docker-compose.yml` | Orchestrates 4 services: `mysql`, `app`, `prometheus`, `grafana` on a shared bridge network with named volumes for persistence. |
| `.dockerignore` / `.gitignore` | Keep `target/`, IDE files, and the Maven wrapper jar out of the Docker build context and git history. |
| `prometheus/prometheus.yml` | Scrape config: scrapes the app's `/actuator/prometheus` every 5s, plus Prometheus's own `/metrics`. |
| `grafana/provisioning/datasources/datasource.yml` | Auto-registers the Prometheus datasource on Grafana startup — no manual UI setup needed. |
| `grafana/provisioning/dashboards/dashboard-provider.yml` | Tells Grafana to load any dashboard JSON found in `/var/lib/grafana/dashboards` on startup/every 30s. |
| `grafana/dashboards/application-monitoring-dashboard.json` | The pre-built 9-panel "Application Monitoring Dashboard" (see Docker section below). |
| `src/main/java/.../ApplicationMonitoringDemoApplication.java` | `@SpringBootApplication` + `@EnableScheduling` (required for `TrafficSimulator`'s `@Scheduled` methods). |
| `config/MetricsConfig.java` | Registers the `TimedAspect` bean (activates `@Timed`) and a `MeterRegistryCustomizer` that stamps every metric with `application=springboot-monitoring-demo` and denies noisy `/actuator/*` URI tags other than `/actuator/prometheus`. |
| `domain/Order.java`, `OrderStatus.java` | JPA entity + lifecycle enum. |
| `repository/OrderRepository.java` | Spring Data JPA repo; `findByStatus` (paged) and `countByStatus` (used by the `orders_active` gauge). |
| `dto/*` | `OrderRequest` (validated input), `OrderResponse` (output projection), `OrderStatusUpdateRequest`, generic `PageResponse<T>` wrapper. |
| `exception/*` | `OrderNotFoundException` (→ 404), `InvalidOrderStateTransitionException` (→ 409). |
| `service/OrderService.java` | All business logic + metric registration/recording; the state-machine `ALLOWED_TRANSITIONS` map. |
| `simulation/TrafficSimulator.java` | `@Scheduled` component generating synthetic order creation and progression traffic; disabled via `monitoring.simulation.enabled=false`. |
| `web/OrderController.java` | REST endpoints. |
| `web/ApiError.java`, `GlobalExceptionHandler.java` | Uniform JSON error shape + `@RestControllerAdvice` mapping exceptions to HTTP statuses. |
| `src/main/resources/application.yml` | All configuration (see section 4). |
| `src/main/resources/db/migration/V1__init_schema.sql` | Flyway migration creating the `orders` table + indexes. |
| `src/test/java/.../service/OrderServiceTest.java` | Mockito-based unit tests of business logic and metric side-effects using a real `SimpleMeterRegistry`. |
| `src/test/java/.../web/OrderControllerTest.java` | `@WebMvcTest` slice tests of the controller/validation/exception-mapping layer (`@MockitoBean` for the service). |
| `src/test/java/.../OrderApiIntegrationTest.java` | `@SpringBootTest` + Testcontainers MySQL full-stack test, verifying persistence and that `/actuator/prometheus` exposes `orders_creation_total`. |
| `src/test/resources/application-test.yml` | Test profile: keeps Flyway on, `ddl-auto: validate`, forces `monitoring.simulation.enabled: false`. |

---

## 6. Getting Started

### Prerequisites
- Docker + Docker Compose v2
- (Optional, for local non-Docker dev) JDK 25 and Maven 3.9+

### Run everything with Docker Compose

```bash
git clone https://github.com/JNikhilSakthi/application-monitoring.git
cd application-monitoring

# Build and start MySQL, the app, Prometheus, and Grafana
docker-compose up --build
```

This starts, in dependency order:
1. `mysql` — waits for a healthy `mysqladmin ping` before...
2. `app` starts, runs Flyway migrations, boots on port `8080`, and (with `SIMULATION_ENABLED=true`) immediately starts generating order traffic.
3. `prometheus` starts scraping `app:8080/actuator/prometheus` every 5s.
4. `grafana` starts with the Prometheus datasource and dashboard already provisioned.

### Access the services
| Service | URL | Credentials |
|---|---|---|
| Order API | http://localhost:8080/api/orders | — |
| Actuator / Prometheus scrape endpoint | http://localhost:8080/actuator/prometheus | — |
| Prometheus UI | http://localhost:9090 | — |
| Grafana | http://localhost:3000 | `admin` / `admin` |

Open Grafana → Dashboards → **Application Monitoring Dashboard** — it should already be moving within a few seconds because of `TrafficSimulator`.

### Stop / clean up
```bash
docker-compose down          # stop containers, keep volumes (data persists)
docker-compose down -v       # stop and delete volumes (fresh MySQL/Prometheus/Grafana state)
```

### Run locally without Docker (app only)
```bash
mvn spring-boot:run \
  -DDB_HOST=localhost -DDB_PORT=3306 -DDB_NAME=monitoring_demo \
  -DDB_USERNAME=monitoring_user -DDB_PASSWORD=monitoring_pass
```
(requires a MySQL instance reachable at those coordinates, e.g. via `docker-compose up mysql`).

---

## 7. API Documentation

Base path: `/api/orders`. All responses are JSON; errors follow the `ApiError` shape.

### Create an order
`POST /api/orders`

Request:
```json
{
  "customerName": "Alice",
  "product": "Widget",
  "quantity": 2,
  "unitPrice": 10.00
}
```
Response `201 Created`:
```json
{
  "id": 1,
  "customerName": "Alice",
  "product": "Widget",
  "quantity": 2,
  "unitPrice": 10.00,
  "totalAmount": 20.00,
  "status": "PENDING",
  "createdAt": "2026-07-22T10:00:00Z",
  "updatedAt": "2026-07-22T10:00:00Z"
}
```
Validation failure → `400 Bad Request`:
```json
{
  "timestamp": "2026-07-22T10:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/orders",
  "details": ["quantity: quantity must be at least 1"]
}
```

### Get an order
`GET /api/orders/{id}` → `200 OK` with an `OrderResponse`, or `404 Not Found` if the id doesn't exist.

### List orders (paged, optional status filter)
`GET /api/orders?status=PENDING&page=0&size=20`

Response `200 OK`:
```json
{
  "content": [ { "id": 1, "customerName": "Alice", "...": "..." } ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "last": true
}
```

### Update order status
`PATCH /api/orders/{id}/status`

Request:
```json
{ "status": "PROCESSING" }
```
Response `200 OK` with the updated `OrderResponse`, or `409 Conflict` if the transition is invalid (e.g. `COMPLETED → PROCESSING`):
```json
{
  "timestamp": "2026-07-22T10:00:00Z",
  "status": 409,
  "error": "Conflict",
  "message": "Cannot transition order from COMPLETED to PROCESSING",
  "path": "/api/orders/1/status",
  "details": []
}
```

**Valid transitions:** `PENDING → {PROCESSING, CANCELLED}`, `PROCESSING → {COMPLETED, FAILED, CANCELLED}`. `COMPLETED`, `FAILED`, `CANCELLED` are terminal.

### Cancel an order
`DELETE /api/orders/{id}` — shorthand for `PATCH .../status` with `CANCELLED`. Returns `200 OK` with the updated `OrderResponse`, or `409` if the order is already in a terminal state.

### Actuator endpoints
- `GET /actuator/health` — liveness/readiness + DB health.
- `GET /actuator/metrics` — ad-hoc metric inspection (e.g. `/actuator/metrics/orders_active`).
- `GET /actuator/prometheus` — the full Prometheus exposition-format metric dump; this is what Prometheus scrapes every 5s.

---

## 8. Testing

```bash
mvn test
```

- **`OrderServiceTest`** (Mockito + a real `SimpleMeterRegistry`) — unit-tests business logic: order creation and total calculation, `OrderNotFoundException` on missing id, valid/invalid state transitions, and asserts the actual counter/summary values (`orders_creation_total`, `order_value_amount_currency`, `orders_failed_total`, `orders_cancelled_total`) after each operation — proving metrics and business logic stay in sync.
- **`OrderControllerTest`** (`@WebMvcTest`) — slice-tests the HTTP layer in isolation (service mocked via `@MockitoBean`): 201 on create, 400 on validation failure, 404/409 mapping via `GlobalExceptionHandler`, paged list response shape, cancel endpoint.
- **`OrderApiIntegrationTest`** (`@SpringBootTest` + Testcontainers `MySQLContainer`) — full-stack test: real MySQL, real Flyway migration, creates an order over HTTP and asserts both the persisted response *and* that `/actuator/prometheus` now contains `orders_creation_total`; also checks `/actuator/health` returns `UP`. Requires Docker; this is the one test class that could not be executed inside this sandbox (nested Docker-in-Docker socket restriction) but runs normally in any standard CI/dev environment with Docker access — this constraint predates and is unrelated to the Spring Boot 4 migration, and was independently confirmed by running the packaged jar against a real MySQL container and curling `/actuator/health` and `/actuator/prometheus` directly. `monitoring.simulation.enabled=false` in `application-test.yml` keeps the dataset deterministic during tests.

---

## 9. Docker

**`Dockerfile`** — two stages:
1. **Build stage** (`maven:3.9-eclipse-temurin-25`): copies `pom.xml` first and runs `dependency:go-offline` to cache dependencies in a separate layer, then copies `src/` and runs `clean package -DskipTests` (tests are run separately in CI, not at image-build time).
2. **Runtime stage** (`eclipse-temurin:25-jre-jammy`): copies only the built jar (`springboot-monitoring-demo.jar`) into a minimal JRE image, creates and switches to a non-root `spring` user, exposes port `8080`.

**`docker-compose.yml`** — four services on one bridge network (`monitoring-net`):
- **`mysql`** (8.0.36) — seeded via env vars, healthchecked with `mysqladmin ping`, data persisted in the `mysql-data` volume; `app` waits on `service_healthy` before starting.
- **`app`** — built from the local `Dockerfile`; DB connection and `SIMULATION_ENABLED=true` passed as environment variables; port `8080` published.
- **`prometheus`** (v2.54.1) — mounts `prometheus/prometheus.yml` read-only and a `prometheus-data` volume; port `9090` published; depends on `app` (so its first scrape target exists, though Prometheus itself tolerates a target being briefly down).
- **`grafana`** (11.2.0) — admin credentials set via env vars, provisioning folders (`datasources/`, `dashboards/`) and the dashboard JSON mounted read-only, `grafana-data` volume for persisted state; port `3000` published; depends on `prometheus`.

**The provisioned dashboard** (`grafana/dashboards/application-monitoring-dashboard.json`) ships 9 panels, refreshing every 5s over the last 15 minutes:
1. Orders Created (rate/min) — `rate(orders_creation_total[1m]) * 60`
2. Orders Failed vs Cancelled (rate/min)
3. Active Orders — live gauge of `orders_active`
4. HTTP Request Rate by Status — `sum(rate(http_server_requests_seconds_count[1m])) by (status)`
5. HTTP Request Latency p95 — `histogram_quantile(0.95, ...http_server_requests_seconds_bucket...)`
6. Order Processing Time p50/p95/p99 — `histogram_quantile` over `order_processing_time_seconds_bucket`
7. Order Value Distribution (avg) — `rate(order_value_amount_currency_sum[5m]) / rate(order_value_amount_currency_count[5m])`
8. JVM Heap Memory Used — `jvm_memory_used_bytes{area="heap"}`
9. Process/System CPU Usage — `process_cpu_usage`, `system_cpu_usage`

---

## 10. Interview Preparation

**Q: What's the difference between Micrometer and Prometheus?**
Micrometer is a *facade* — a vendor-neutral metrics API embedded in Spring Boot Actuator, analogous to SLF4J for logging. It doesn't store or query data itself; you attach a *registry* implementation (here, `micrometer-registry-prometheus`) that knows how to format metrics in a specific backend's wire format. Swapping to Datadog or CloudWatch later would mean swapping the registry dependency, not rewriting instrumentation code.

**Q: Why is `orders_active` a gauge backed by a repository query instead of an in-memory counter incremented/decremented on each transition?**
An in-memory counter drifts: it resets on restart, and it's wrong the moment you run more than one instance behind a load balancer (each instance would only know about its own increments/decrements). Deriving the gauge from `count(status IN (PENDING, PROCESSING))` in the database means it is always an accurate reflection of true state, recomputed fresh on every Prometheus scrape, regardless of restarts or horizontal scaling — at the cost of a DB query per scrape, which is an acceptable tradeoff at a 5s scrape interval for read-indexed columns.

**Q: Counter vs. Gauge vs. Timer vs. DistributionSummary — when do you use each?**
- **Counter** — monotonically increasing (`orders_creation_total`); use `rate()` in PromQL to get a per-second/per-minute rate.
- **Gauge** — a value that can go up or down at any time (`orders_active`, JVM heap used).
- **Timer** — counts *and* times an operation, producing count/sum/max and (if histogram enabled) buckets for percentile queries (`order_processing_time_seconds`).
- **DistributionSummary** — like a Timer but for a non-time quantity (`order_value_amount_currency` tracks order dollar amounts, not durations).

**Q: How do you get percentiles (p95/p99) out of Prometheus when Prometheus itself has no native percentile function?**
Publish a histogram (`.publishPercentileHistogram()` in Micrometer, or `slo:` fixed buckets), which emits `_bucket{le="..."}` series, then use PromQL's `histogram_quantile(0.95, sum(rate(metric_bucket[5m])) by (le, ...))`. This is a *client-side approximate* percentile computed from bucket boundaries — different from Micrometer's own in-process `.publishPercentiles()` (which computes exact client-side percentiles but can't be aggregated across instances after the fact). Understanding this distinction — aggregatable histogram buckets vs. non-aggregatable pre-computed percentiles — is one of the most common Prometheus interview probes.

**Q: What does `@Timed` + `TimedAspect` actually do?**
`@Timed` is inert on its own — it's just metadata. `TimedAspect` (registered as a bean in `MetricsConfig`) is a Spring AOP aspect that intercepts calls to `@Timed`-annotated methods and wraps them in a Micrometer `Timer`, producing `order_service_create_seconds`-style method-level latency histograms without hand-writing `Timer.Sample` boilerplate in every method (the project does both: `@Timed` for simple per-method timing, and manual `Timer.Sample`/`Counter` calls in `OrderService` where business-specific tagging/logic is needed on transition).

**Q: Why does Prometheus *pull* metrics instead of applications *pushing* them?**
Pull-based scraping means Prometheus, not the app, controls collection cadence and load; a crashed or slow-scraping target is visible as `up == 0` rather than silently missing data; and it avoids every instrumented service needing to know a collector's address/credentials. The tradeoff is that Prometheus needs network-level visibility into every target (via service discovery) and short-lived/batch jobs need a Pushgateway workaround since they may not live long enough to be scraped.

**Q: Why is the order-creation counter named `orders_creation_total` instead of the more natural `orders_created_total`?**
Micrometer 1.16 (bundled with Spring Boot 4.0) ships the Prometheus Java client 1.4.x, whose OpenMetrics-aware naming convention treats a literal `_created` token as the reserved suffix for a counter's companion "created-timestamp" series. A counter named `orders_created_total` therefore gets silently rewritten to `orders_total` on scrape — same value, wrong name, and any dashboard or alert querying `orders_created_total` would silently stop matching after an upgrade. This was only caught by scraping `/actuator/prometheus` after the migration, not by reading a changelog — a good example of why "compiles and unit tests pass" isn't sufficient proof a metrics pipeline migration is safe.

**Common mistakes:**
- Forgetting `percentiles-histogram: true` and then being confused why `histogram_quantile()` returns nothing — without it, only `_count`/`_sum` are published, no `_bucket` series.
- Unbounded metric cardinality — tagging a metric with something like a raw customer ID or order ID would create a new time series per unique value and can silently blow up Prometheus memory/storage (a very common real production incident). This project deliberately tags only by bounded dimensions (`status`, `uri`, `application`).
- Leaving Actuator's `env`, `heapdump`, `shutdown`, or `beans` endpoints exposed in `management.endpoints.web.exposure.include` in production — this project's explicit allowlist (`health, info, prometheus, metrics`) is the pattern to copy.
- Using `ddl-auto: update`/`create` in a service that also runs Flyway — the two can fight over schema ownership; this project's `validate` avoids that entirely.
- On Spring Boot 4 specifically: adding `flyway-core` directly (instead of `spring-boot-starter-flyway`) and having migrations silently never run; naming a counter with `_created` in it and having it silently renamed on scrape; and relying on Lombok's classpath auto-discovery on JDK 25+ instead of an explicit `annotationProcessorPaths` entry.

**Production considerations:**
- Set explicit JVM `-Xmx`/`-Xms` and container memory limits and watch `jvm_memory_used_bytes` vs. limit on the dashboard — Micrometer's JVM metrics are what container autoscalers (e.g., KEDA) or manual capacity decisions are frequently based on.
- Add Prometheus **alerting rules** (not present in this demo) on top of these metrics — e.g., burn-rate alerts on the `http_server_requests_seconds` SLO buckets, or a rate-of-`orders_failed_total` alert.
- Secure `/actuator/prometheus` in real deployments (network policy / internal-only ingress) since metrics can leak internal topology and business volume information.
- In multi-instance deployments, ensure Prometheus is configured with proper service discovery (Kubernetes SD, not a static target list like this demo's) so scaling in/out doesn't require manual `prometheus.yml` edits.
- Set MySQL `useSSL=true` with proper certs in production; this demo's `useSSL=false` is a local-convenience-only setting.

**Performance notes:**
- A 5-second scrape interval (this project's `prometheus.yml`) is aggressive and fine for a demo/small service; production Prometheus deployments commonly use 15-30s to control storage/cardinality growth, tightening only for metrics that need faster alerting reaction time.
- Percentile histograms have a storage cost proportional to bucket count × label cardinality; keep tag dimensions on histogram metrics low (this project tags `http_server_requests_seconds` mainly by `uri`/`status`/`method`, not anything higher-cardinality).
- The `orders_active` gauge recomputing via a DB query on every scrape is O(1) thanks to `idx_orders_status`, but at very high scrape frequency or very large tables this pattern should be revisited (e.g., cached with a short TTL) — a good "what would you improve" interview talking point.

---

## License

MIT — see [LICENSE](./LICENSE).
