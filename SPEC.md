# rsai-backend — Technical Specification

> **Audience**: AI assistants and engineers onboarding to this codebase.  
> **Last updated**: 2026-05-04  
> **Version**: seed schema v6

---

## 1. Project Overview

`rsai-backend` is the REST API backend for a Japanese high-school festival event guide app (r-sai 2026). It serves:

- Event schedule data (projects, locations, timetables, categories)
- Food vendor data (foods, menus)
- Venue map data
- Real-time congestion levels per location
- Announcements (including emergency notices)
- A privileged admin interface for database management

The app is a single-binary Java application built on the **Gate** framework — a custom lightweight HTTP framework built in this repository on top of Jetty with Java 21 virtual thread support.

---

## 2. Deployment Context

```
Browser / Mobile App
      │
      ▼
Cloudflare Proxy (edge)
      │  injects CF-Connecting-IP, X-Forwarded-For, CF-Access-Jwt-Assertion
      ▼
Azure Container Apps (Envoy sidecar rewrites remoteAddr)
      │  X-Forwarded-For: <real-client-ip>, <cloudflare-edge-ip>
      ▼
Java process (rsai-backend, port from $PORT env var)
      │
      ▼
MySQL 8.4 (Azure Database for MySQL / GCP Cloud SQL)
```

- **Production URL (admin)**: `https://admin.r-sai2026.site`
- **CORS allowed origins**: controlled via `CORS_ALLOWED_ORIGIN` env var (default: `https://admin.r-sai2026.site`); extra dev origins via `CORS_ALLOWED_EXTRA_ORIGINS`
- **Port**: `$PORT` env var (Azure/Cloud Run injection) overrides `config.yml`

---

## 3. Technology Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| HTTP framework | Gate (custom, this repo) built on Jetty |
| Thread model | Java 21 virtual threads |
| Database | MySQL 8.4 |
| Connection pool | HikariCP |
| JSON | Jackson (ObjectMapper) |
| Build | Gradle 9.x |
| JWT validation | Manual RS256 via `java.security` (no JWT library) |

---

## 4. Startup Sequence

```
Main.main()
  1. Read version from /version.txt resource
  2. ConfigLoader.load()          — load config.yml + env var overrides
  3. Resolve port ($PORT or config.getPort())
  4. Database.init(config.getDatabase())
       — schema.sql executed (infrastructure tables: seed_version, congestion_status, metrics_hourly, metrics_endpoints)
  5. DataSeeder.seed()            — versioned application schema migration (v0 → v6)
  6. CfAccessAuth cfAccessAuth = new CfAccessAuth()
  7. cfAccessAuth.prefetchJwks()  — eager JWKS fetch to eliminate cold-start latency
  8. RequestMetrics.get().init()  — start 5-minute DB persistence scheduler
  9. Register JVM shutdown hook: RequestMetrics.get().shutdown()
 10. Gate gate = new Gate()
 11. gate.cors(corsValue)         — set CORS allowed origins
 12. gate.before(RequestMetrics.get()::startTimer)
 13. gate.before(new CloudflareIpFilter())
 14. gate.before(new ApiKeyAuth())
 15. gate.before(cfAccessAuth)
 16. Register route: GET /health
 17. gate.register(new DataController())
 18. gate.register(new CongestionController())
 19. gate.register(new AdminController())
 20. gate.register(new AnnouncementsController())
 21. gate.after(SecurityHeaders.get()::handle)
 22. gate.after(RequestMetrics.get()::record)
 23. gate.start(port)
 24. server.join()                — block main thread
```

---

## 5. Configuration

### config.yml (`src/main/resources/config.yml`)

```yaml
port: 8080
env: development
name: MyApp
database:
  host: localhost
  port: 3306
  name: rsai
  user: root
  password: ""
  cloudSqlInstance: ""   # GCP Cloud SQL (project:region:instance)
  maxPoolSize: 33
```

### Environment Variable Overrides

| Variable | Config field / Purpose |
|---|---|
| `PORT` | HTTP server port (Azure/Cloud Run) |
| `DB_HOST` | `database.host` |
| `DB_PORT` | `database.port` |
| `DB_NAME` | `database.name` |
| `DB_USER` | `database.user` |
| `DB_PASSWORD` | `database.password` |
| `CLOUD_SQL_INSTANCE` | `database.cloudSqlInstance` (GCP Cloud SQL) |
| `API_KEY` | **Required.** Admin API key for `X-API-Key` header |
| `READ_ONLY_KEY` | Optional. Read-only API key (GET requests only) |
| `CORS_ALLOWED_ORIGIN` | Primary allowed origin (default: `https://admin.r-sai2026.site`) |
| `CORS_ALLOWED_EXTRA_ORIGINS` | Comma-separated extra origins appended to CORS (dev use) |
| `CF_ACCESS_AUD` | Cloudflare Access audience tag |
| `CF_ACCESS_TEAM_DOMAIN` | Cloudflare team domain (short name or FQDN) |
| `CF_ACCESS_DEV_DISABLE` | Set `true` to disable CF JWT validation in development |
| `SKIP_CF_IP_CHECK` | Set `true` to bypass Cloudflare IP range validation in development |

---

## 6. Security Architecture

Three `before` filters execute in order on every request. Filter pipeline:

```
CloudflareIpFilter → ApiKeyAuth → CfAccessAuth → route handler
```

### 6.1 CloudflareIpFilter

**Purpose**: Reject requests not originating from Cloudflare's published IP ranges.

**Behavior**:
- Exempt paths: `/health`
- Parses `X-Forwarded-For` header, takes the rightmost non-private IP (the Cloudflare edge IP added by Azure's trusted Envoy sidecar)
- Checks that IP against 15 IPv4 and 7 IPv6 Cloudflare CIDR blocks (hardcoded, last updated 2026-04-26)
- On failure: `403 { "error": "Forbidden" }` + `halt()`
- Bypass: `SKIP_CF_IP_CHECK=true` (logs a warning)

**Private IP detection**: uses `InetAddress.isLoopbackAddress()`, `isSiteLocalAddress()` (RFC1918: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16), `isLinkLocalAddress()`

**Cloudflare CIDR ranges**:
```
IPv4: 173.245.48.0/20, 103.21.244.0/22, 103.22.200.0/22, 103.31.4.0/22,
      141.101.64.0/18, 108.162.192.0/18, 190.93.240.0/20, 188.114.96.0/20,
      197.234.240.0/22, 198.41.128.0/17, 162.158.0.0/15, 104.16.0.0/13,
      104.24.0.0/14, 172.64.0.0/13, 131.0.72.0/22
IPv6: 2400:cb00::/32, 2606:4700::/32, 2803:f800::/32, 2405:b500::/32,
      2405:8100::/32, 2a06:98c0::/29, 2c0f:f248::/32
```

### 6.2 ApiKeyAuth

**Purpose**: Authenticate all non-health, non-preflight requests via `X-API-Key` header.

**Behavior**:
- Exempt: `/health` path, `OPTIONS` method (CORS preflight)
- Header: `X-API-Key`
- Two keys:
  - `API_KEY` env var (required): full admin access — all methods allowed
  - `READ_ONLY_KEY` env var (optional): GET requests only; POST/PUT/DELETE → `403 { "error": "Forbidden: read-only access" }`
- Missing header or wrong key: `401 { "error": "Unauthorized" }` + `halt()`
- If `API_KEY` is not set at startup: throws `IllegalStateException` (hard fail)

### 6.3 CfAccessAuth

**Purpose**: Validate Cloudflare Access JWT for `/admin/*` endpoints only.

**Behavior**:
- Exempt: `/health` path, `OPTIONS` method, any path not starting with `/admin`
- Header: `CF-Access-Jwt-Assertion`
- Algorithm: RS256 only (rejects others)
- Claims validated: `exp` (expiry), `nbf` (not-before), `aud` (audience vs `CF_ACCESS_AUD`)
- Email extracted from `email` claim and stored as request attribute `cf_verified_email`
- JWKS URL: `https://<CF_ACCESS_TEAM_DOMAIN>/cdn-cgi/access/certs`
- JWKS cache TTL: 1 hour (double-checked locking to prevent concurrent refreshes)
- `prefetchJwks()` called at startup to eliminate cold-start latency
- Disabled when both `CF_ACCESS_AUD` and `CF_ACCESS_TEAM_DOMAIN` are unset AND `CF_ACCESS_DEV_DISABLE=true`
- If either required env var is missing without dev flag: throws `IllegalStateException` (hard fail)

**JWKS refresh logic**:
1. Fast path (no lock): return from cache if non-empty and not expired
2. Slow path (synchronized): re-check under lock, call `refreshKeysLocked()` if still stale
3. `refreshKeysLocked()`: HTTP GET to certs URL, parse RSA public keys from JWK set, atomically replace cache

### 6.4 Security Response Headers (after filter)

`SecurityHeaders` is a singleton after-filter that appends these headers to every response:

| Header | Value |
|---|---|
| `X-Frame-Options` | `DENY` |
| `X-Content-Type-Options` | `nosniff` |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` |
| `Content-Security-Policy` | `default-src 'none'` |
| `Referrer-Policy` | `no-referrer` |
| `Permissions-Policy` | `geolocation=(), camera=(), microphone=()` |

### 6.5 CORS Configuration

- `Access-Control-Allow-Origin`: from env vars (see §5)
- `Access-Control-Allow-Methods`: `GET, POST, PUT, DELETE, PATCH, OPTIONS`
- `Access-Control-Allow-Headers`: `Content-Type, Authorization, X-API-Key`
- `Access-Control-Max-Age`: `86400`
- `Access-Control-Allow-Credentials`: `true` (only when origin is not `*`)
- Preflight `OPTIONS` requests return `204` after before-filters run

---

## 7. API Endpoints

All endpoints except `/health` require `X-API-Key` header. `/admin/*` additionally requires `CF-Access-Jwt-Assertion`.

### 7.1 Health

#### `GET /health`
No authentication required.

**Response** `200`:
```json
{ "status": "ok" }
```

---

### 7.2 DataController — Event Data (5-minute cache)

All three endpoints use an in-process 5-minute TTL cache (per-key `ConcurrentHashMap`). Cache is populated from DB on first request or after expiry. On DB error: `503 { "error": "Service temporarily unavailable" }`.

#### `GET /events`
Returns all event-related data in a single payload.

**Response** `200`:
```json
{
  "categories": [
    { "id": 1, "name": "ステージ系" }
  ],
  "locations": [
    {
      "id": 1, "name": "体育館", "floor": 1,
      "svgId": "gym",           // string | absent if null
      "isStage": true,
      "tracksCongestion": true,
      "x": 100.0,               // number | absent if null
      "y": 200.0                // number | absent if null
    }
  ],
  "projects": [
    {
      "id": 1, "title": "...",
      "organizer": "...",       // absent if null
      "description": "...",     // absent if null
      "imageUrl": "...",        // absent if null
      "locationId": 2           // absent if null
    }
  ],
  "projectCategories": [
    { "projectId": 1, "categoryId": 1 }
  ],
  "timetables": [
    {
      "id": 1, "projectId": 1, "locationId": 2,
      "date": "2026-07-04",
      "isAllDay": false,
      "start": "10:00:00",      // absent if null
      "end": "11:00:00"         // absent if null
    }
  ]
}
```

SQL order: categories by `id`, locations by `floor, id`, projects by `id`, timetables by `event_date, start_time`.

#### `GET /food`
Returns food vendor and menu data.

**Response** `200`:
```json
{
  "foods": [
    {
      "id": 1, "name": "キッチンカー店舗（未定）",
      "description": "...",   // absent if null
      "imageUrl": "..."        // absent if null
    }
  ],
  "menus": [
    {
      "id": 1, "foodId": 1, "name": "メニュー（未定）",
      "price": 500,            // absent if null
      "description": "...",    // absent if null
      "isSoldOut": false       // absent if null
    }
  ]
}
```

SQL order: foods by `id`, menus by `food_id, id`.

#### `GET /map`
Returns location data only (subset of `/events`).

**Response** `200`:
```json
{
  "locations": [
    {
      "id": 1, "name": "体育館", "floor": 1,
      "svgId": "gym",
      "isStage": true,
      "tracksCongestion": true,
      "x": 100.0,
      "y": 200.0
    }
  ]
}
```

---

### 7.3 CongestionController — Congestion Tracking

#### `GET /locations`
Returns all locations where `tracks_congestion = 1`, with the title of the first scheduled project.

**Response** `200` — JSON array:
```json
[
  {
    "id": 1, "name": "体育館", "floor": 1,
    "svgId": "gym",         // absent if null
    "x": 100.0,             // absent if null
    "y": 200.0,             // absent if null
    "project": "演劇（タイトル未定）"  // absent if null — first timetable entry by date/start_time
  }
]
```

SQL: `WHERE tracks_congestion = 1`, ordered `floor, id`. Project resolved via correlated subquery on `timetables JOIN projects`.

On DB error: `503 { "error": "...", "detail": "..." }`

#### `GET /congestion`
Returns current congestion level for all tracked locations.

**Response** `200` — JSON array:
```json
[
  {
    "location_id": 1,
    "level": 1,
    "updated_at": "2026-07-04 10:30:00",
    "updated_by": "user@example.com"
  }
]
```

`level` values: `0` = low, `1` = medium, `2` = high.

On DB error: `503 { "error": "...", "detail": "..." }`

#### `POST /congestion/{id}`
Updates congestion level for a location. **Requires CF Access JWT** (via `updated_by` field from verified email).

Wait — actually `POST /congestion/{id}` does NOT require CF Access JWT (CfAccessAuth only protects `/admin/*`). `updated_by` falls back to `"unknown"` if the attribute is absent.

**Path param**: `{id}` — integer location ID

**Request body**:
```json
{ "level": 1 }
```

**Validation**:
- `level` must be present: `400 { "error": "level required" }`
- `level` must be 0–2: `400 { "error": "level must be 0-2" }`
- Invalid `{id}` (non-integer): `400 { "error": "Invalid id" }`

**Response** `200`:
```json
{ "ok": true, "location_id": 1, "level": 1 }
```

**SQL**: `INSERT INTO congestion_status ... ON DUPLICATE KEY UPDATE level, updated_at, updated_by`  
`updated_by` is taken from request attribute `cf_verified_email` (set by CfAccessAuth if CF Access JWT was provided), falling back to `"unknown"`.  
`updated_at` is set to server-local time formatted as `"yyyy-MM-dd HH:mm:ss"`.

On DB error: `503 { "error": "...", "detail": "..." }`

---

### 7.4 AnnouncementsController

#### `GET /announcements`
Returns currently visible announcements filtered by display window.

**Response** `200`:
```json
{
  "announcements": [
    {
      "id": 1,
      "content": "ここにお知らせを表示できます（テスト表示）",
      "isEmergency": false,
      "displayFrom": "2026-07-01 00:00:00",   // null if not set
      "displayUntil": "2026-07-06 00:00:00"   // null if not set
    }
  ]
}
```

**Filter SQL** (simplified):
```sql
WHERE (display_from IS NULL OR display_from <= NOW())
  AND (display_until IS NULL OR display_until >= NOW())
ORDER BY is_emergency DESC, id DESC
```

Rows with `display_from IS NULL` and `display_until IS NULL` are always shown. Ordered emergency-first, then newest-first within priority.

---

### 7.5 AdminController — Database Management

All endpoints under `/admin/*` require both `X-API-Key` and `CF-Access-Jwt-Assertion`. Responses include `Cache-Control: no-store` on list endpoints.

#### SQL injection prevention:
- Table and column names validated via `isValidIdentifier(s)`: must match `[a-zA-Z0-9_]+`
- Column types validated against `ALLOWED_COL_TYPES` allowlist: `INT, BIGINT, VARCHAR(255), VARCHAR(100), TEXT, TINYINT(1), FLOAT, DOUBLE, DATE, DATETIME, TIME`
- Column values use `PreparedStatement` placeholders (never string-interpolated)
- Default values in `addColumn` validated against `[a-zA-Z0-9._\-]+`
- `execSql` executes raw SQL — no injection prevention (privileged endpoint)

#### `GET /admin/tables`
List all tables in the current database with approximate row counts.

**Response** `200` — JSON array:
```json
[
  { "name": "announcements", "rowCount": 2 },
  { "name": "categories",    "rowCount": 5 }
]
```

Source: `INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE()`, ordered by `TABLE_NAME`.

#### `GET /admin/tables/{table}`
Get schema and data for a specific table (up to 500 rows).

**Path param**: `{table}` — validated identifier

**Response** `200`:
```json
{
  "cols": [
    { "name": "id",   "type": "INT",         "pk": true },
    { "name": "name", "type": "VARCHAR" }
  ],
  "rows": [
    { "id": 1, "name": "ステージ系" }
  ]
}
```

`"pk": true` only present on primary key columns. Rows capped at 500. Type names from `DatabaseMetaData.getColumns()`.

#### `POST /admin/tables/{table}`
Insert a new row.

**Path param**: `{table}` — validated identifier

**Request body**: JSON object with column names as keys. Boolean values are normalized to `1`/`0`.

**Response** `200`:
```json
{ "id": 6 }          // if AUTO_INCREMENT key returned
{ "ok": true }       // if no generated key
```

**Error responses**:
- `400` on constraint violation (duplicate, null, FK): human-readable message
- `400` on SQL syntax error
- `400` on type mismatch (MySQL 1292/1366)
- `503` on other DB errors

#### `PUT /admin/tables/{table}/{pk}`
Update a row by primary key.

**Path params**: `{table}` (validated identifier), `{pk}` (PK value as string)

**Request body**: JSON object of columns to update (PK column is excluded from SET clause automatically).

**Response** `200`:
```json
{ "updated": 1 }
```

**Error responses**: same codes as `POST /admin/tables/{table}`.

#### `DELETE /admin/tables/{table}/{pk}`
Delete a row by primary key.

**Path params**: `{table}` (validated identifier), `{pk}` (PK value as string)

**Response** `200`:
```json
{ "deleted": 1 }
```

**Error responses**: `400` on FK violation; `503` on other DB errors.

#### `POST /admin/ddl/tables`
Create a new table.

**Request body**:
```json
{
  "name": "my_table",
  "columns": [
    { "name": "id",    "type": "INT",          "pk": true, "autoIncrement": true, "notNull": true },
    { "name": "label", "type": "VARCHAR(255)",  "notNull": false }
  ]
}
```

- `name` and each column `name` must match `[a-zA-Z0-9_]+`
- `type` must be in `ALLOWED_COL_TYPES`
- `pk` columns are implicitly `NOT NULL`

**Response** `200`: `{ "ok": true }`

**Error responses**: `400` on invalid name/type/SQL error; `503` on other DB errors.

#### `POST /admin/ddl/tables/{table}/columns`
Add a column to an existing table.

**Request body**:
```json
{
  "name": "new_col",
  "type": "VARCHAR(255)",
  "notNull": false,
  "defaultValue": "active"
}
```

- `name` must match `[a-zA-Z0-9_]+`
- `type` must be in `ALLOWED_COL_TYPES`
- `defaultValue` (if provided) must match `[a-zA-Z0-9._\-]+`

**Response** `200`: `{ "ok": true }`

#### `POST /admin/sql`
Execute arbitrary SQL. Semicolon-delimited multi-statement. Returns result of last statement.

**Request body**: `{ "sql": "SELECT * FROM categories; SELECT COUNT(*) FROM projects" }`

**Response** `200` (SELECT):
```json
{
  "cols": [ { "name": "id", "type": "int" } ],
  "rows": [ { "id": 1 } ]
}
```

**Response** `200` (DML):
```json
{ "affected": 1 }
```

**Error response**: `400 { "error": "<MySQL error message>" }`

#### `GET /admin/stats`
Returns request metrics and system status.

**Response** `200`:
```json
{
  "totalRequests": 1500,
  "errorRate": 0.02,
  "p50ms": 12,
  "p95ms": 45,
  "instances": 1,
  "maxInstances": 10,
  "chart": [120, 85, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 95, 110],
  "endpoints": [
    { "method": "GET", "path": "/events", "count": 450 }
  ],
  "system": [
    { "name": "Database",   "status": "ok", "value": "Connected" },
    { "name": "API Server", "status": "ok", "value": "Running" }
  ]
}
```

- `chart`: 24 hourly request counts (circular buffer, index 0 = oldest hour)
- `endpoints`: top 10 endpoints by hit count, `/admin/*` paths excluded
- `errorRate`: float, rounded to 2 decimal places
- `instances`/`maxInstances`: static values (1/10)

---

## 8. Database Schema

### 8.1 Infrastructure Tables (schema.sql — created by Database.init())

```sql
-- Migration version tracker
CREATE TABLE IF NOT EXISTS seed_version (
    id      INT PRIMARY KEY DEFAULT 1,
    version INT NOT NULL DEFAULT 0
);

-- Congestion levels per location
CREATE TABLE IF NOT EXISTS congestion_status (
    location_id INT          PRIMARY KEY,
    level       TINYINT      NOT NULL DEFAULT 0,
    updated_at  DATETIME     NOT NULL,
    updated_by  VARCHAR(100) NOT NULL
);

-- Hourly request counts (keyed by hour epoch)
CREATE TABLE IF NOT EXISTS metrics_hourly (
    hour     BIGINT PRIMARY KEY,
    requests BIGINT NOT NULL DEFAULT 0
);

-- Per-endpoint hit counts
CREATE TABLE IF NOT EXISTS metrics_endpoints (
    endpoint VARCHAR(250) PRIMARY KEY,
    hits     BIGINT NOT NULL DEFAULT 0
);
```

### 8.2 Application Tables (DataSeeder.defineTables() — created at seed v0/v1 and v5)

```sql
CREATE TABLE IF NOT EXISTS categories (
    id   INT          PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS locations (
    id                INT          PRIMARY KEY AUTO_INCREMENT,
    name              VARCHAR(255) NOT NULL,
    floor             INT          NOT NULL DEFAULT 0,
    svg_id            VARCHAR(255),
    is_stage          TINYINT(1)   NOT NULL DEFAULT 1,
    tracks_congestion TINYINT(1)   NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS projects (
    id          INT          PRIMARY KEY AUTO_INCREMENT,
    title       VARCHAR(255) NOT NULL,
    organizer   VARCHAR(255),
    description TEXT,
    image_url   VARCHAR(255),
    location_id INT,
    FOREIGN KEY (location_id) REFERENCES locations(id)
);

CREATE TABLE IF NOT EXISTS project_categories (
    project_id  INT NOT NULL,
    category_id INT NOT NULL,
    PRIMARY KEY (project_id, category_id),
    FOREIGN KEY (project_id)  REFERENCES projects(id),
    FOREIGN KEY (category_id) REFERENCES categories(id)
);

CREATE TABLE IF NOT EXISTS timetables (
    id          INT        PRIMARY KEY AUTO_INCREMENT,
    project_id  INT        NOT NULL,
    location_id INT        NOT NULL,
    event_date  DATE       NOT NULL,
    is_all_day  TINYINT(1) NOT NULL DEFAULT 0,
    start_time  TIME,
    end_time    TIME,
    FOREIGN KEY (project_id)  REFERENCES projects(id),
    FOREIGN KEY (location_id) REFERENCES locations(id)
);

CREATE TABLE IF NOT EXISTS announcements (
    id            INT        PRIMARY KEY AUTO_INCREMENT,
    content       TEXT       NOT NULL,
    is_emergency  TINYINT(1) NOT NULL DEFAULT 0,
    display_from  DATETIME,
    display_until DATETIME
);

CREATE TABLE IF NOT EXISTS foods (
    id          INT  PRIMARY KEY AUTO_INCREMENT,
    name        TEXT NOT NULL,
    description TEXT,
    image_url   TEXT
);

CREATE TABLE IF NOT EXISTS menus (
    id          INT        PRIMARY KEY AUTO_INCREMENT,
    food_id     INT        NOT NULL,
    name        TEXT       NOT NULL,
    price       INT,
    description TEXT,
    is_sold_out TINYINT(1),
    FOREIGN KEY (food_id) REFERENCES foods(id)
);
```

### 8.3 Relationships

```
categories ─────────────── project_categories ─────────────── projects
                                                                   │
locations ─────────────────────────────────────── projects.location_id
    │                                                              │
    └─────────────── timetables.location_id            timetables.project_id
    │
    └─────────────── congestion_status.location_id

foods ──────────────────── menus.food_id
```

---

## 9. DataSeeder Migration System

`DataSeeder.seed()` runs at startup. Reads current version from `seed_version.version`, applies migrations in order, then writes version 6.

### Version History

| Version | Migration | What happened |
|---|---|---|
| 0 | Initial | Fresh DB: `defineTables()` + all seed data inserted |
| 1 | `migrateV1()` | TRUNCATE congestion_status; DROP old tables (events, vendors, rooms, etc. — 21 tables); then `defineTables()` + full re-seed |
| 2 | `migrateV2()` | `ALTER TABLE locations ADD COLUMN tracks_congestion TINYINT(1) NOT NULL DEFAULT 1` |
| 3 | `migrateV3()` | `ALTER TABLE locations ADD COLUMN svg_id VARCHAR(255)` |
| 4 | `migrateV4()` | `ALTER TABLE locations ADD COLUMN is_stage TINYINT(1) NOT NULL DEFAULT 1` |
| 5 | `migrateV5()` | `ALTER TABLE projects DROP COLUMN category_id`; `ALTER TABLE projects ADD COLUMN location_id INT`; `defineTables()` (creates foods, menus, project_categories) |
| 6 | Current | No-op (version gate only) |

### Migration Properties
- **Idempotent**: all migrations use `IF NOT EXISTS`, `INSERT IGNORE`, or try/catch on duplicate-column errors
- **Sequential**: each `if (v <= N)` block applies all migrations from that version forward
- **Seed data**: inserted via `INSERT IGNORE` (safe to re-run)
- `defineTables()` is the canonical DDL source; called from both the v≤1 full-rebuild path and v5 migration

### Seed Data (initial placeholders)

| Table | Rows | Notes |
|---|---|---|
| categories | 5 | ステージ系, クラス企画, 部活, 展示, フード |
| locations | 10 | 体育館, メインステージ, 3-A〜C教室, 4-A〜B教室, 中庭, キッチンカーエリア, 正門前広場 |
| projects | 5 | Placeholder titles for each category |
| project_categories | 5 | One category per project |
| timetables | 5 | Two all-day events, three timed events on 2026-07-04 and 2026-07-05 |
| announcements | 2 | 1 normal, 1 emergency (test placeholders) |
| foods | 1 | One kitchen car placeholder |
| menus | 1 | One menu item (price NULL) |

---

## 10. RequestMetrics

Singleton accessed via `RequestMetrics.get()`. Tracks request metrics in memory, persists to DB every 5 minutes.

### In-Memory Data Structures

| Structure | Type | Purpose |
|---|---|---|
| `totalRequests` | `AtomicLong` | Total request count since startup |
| `errorCount` | `AtomicLong` | Requests with status ≥ 400 |
| `hourlyCounts` | `AtomicLong[24]` | Circular buffer, keyed by `(hour % 24)`, counting requests per hour |
| `responseTimes` | `long[1000]` (ring buffer) | Last 1000 response times in ms (used for p50/p95) |
| `endpointCounts` | `ConcurrentHashMap<String, AtomicLong>` | Hit count per `"METHOD /path"` key, max 100 entries |

### Lifecycle

- `init()`: schedules DB flush every 5 minutes via `ScheduledExecutorService`
- `startTimer(ctx)`: stores `System.currentTimeMillis()` in request attribute `_reqStart`
- `record(ctx)`: calculates elapsed time, increments counters; skips `/admin/*` paths
- `shutdown()`: flushes to DB, shuts down scheduler

### Percentile Calculation

`getPercentiles()` returns `long[2]` = `[p50, p95]`:
- Copies `responseTimes` ring buffer into a sorted array
- p50 = element at index `(size * 50 / 100)`
- p95 = element at index `(size * 95 / 100)`

### DB Persistence

On flush, writes:
1. `metrics_hourly`: `INSERT INTO metrics_hourly (hour, requests) VALUES (?, ?) ON DUPLICATE KEY UPDATE requests = requests + VALUES(requests)` for each non-zero hour slot
2. `metrics_endpoints`: `INSERT INTO metrics_endpoints (endpoint, hits) VALUES (?, ?) ON DUPLICATE KEY UPDATE hits = hits + VALUES(hits)` for each endpoint

### Error Rate

`getErrorRate()` = `errorCount.get() / (double) totalRequests.get()`, returns `0.0` if no requests.

---

## 11. Gate Framework (internal)

The Gate framework lives in `gate-core/src/main/java/dev/gate/core/` and related packages.

### Routing

Two registration modes:

```java
// Annotation-based
gate.register(new MyController());   // scans for @GateController + @*Mapping methods
gate.scan("com.example.pkg");        // auto-discovers @GateController classes from classpath

// Programmatic
gate.get("/ping", ctx -> ctx.result("pong"));
gate.post("/data", ctx -> ctx.json(ctx.bodyAs(Data.class)));
```

Supported annotations: `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@PatchMapping`, `@WsMapping`

Path parameter syntax: `{name}`. URL-decoded (UTF-8). Empty names (`{}`) or duplicate names throw `IllegalArgumentException` at registration time.

**Route priority**: exact routes > pattern routes; within pattern routes, first-registered wins. Trailing slashes stripped before matching.

### Filter Pipeline

```
before filters (registration order)
  → route handler
    → after filters (registration order)
      → write response
```

- `gate.before(handler)` — runs before route handler
- `gate.after(handler)` — always runs, even if before/route threw
- Each after filter runs in its own try/catch (one failure does not stop others)
- `ctx.halt()` — stops pipeline without exception; skips remaining before filters and route handler; after filters still run
- Throw exception in before filter to invoke error handler and skip route handler
- Setting `ctx.status(4xx)` without throwing/halting does NOT stop routing

### Context API

**Request:**

| Method | Return | Description |
|---|---|---|
| `ctx.path()` | `String` | Normalized request path |
| `ctx.method()` | `String` | HTTP method (e.g. `"GET"`) |
| `ctx.pathParam("name")` | `String` or `null` | URL path parameter |
| `ctx.query("key")` | `String` or `null` | Query string parameter |
| `ctx.requestHeader("name")` | `String` or `null` | Request header |
| `ctx.body()` | `String` | Full body (cached, max 1 MB) |
| `ctx.bodyAs(Class<T>)` | `T` or `null` | Jackson JSON deserialization; `null` if body empty |
| `ctx.getAttribute(key)` | `Object` | Request-scoped attribute |
| `ctx.setAttribute(key, val)` | — | Store request-scoped attribute |

**Response (chainable):**

| Method | Description |
|---|---|
| `ctx.result("text")` | `text/plain; charset=utf-8` |
| `ctx.json(object)` | `application/json; charset=utf-8` via Jackson |
| `ctx.status(code)` | Set HTTP status code (default: 200) |
| `ctx.header("K", "V")` | Set response header; throws `IllegalArgumentException` on `\r`/`\n` in key or value |
| `ctx.halt()` | Stop pipeline without exception |

### WebSocket

```java
@WsMapping("/chat")
public void chat(WsContext ctx, String message) {
    if (ctx.isOpen()) ctx.send("Echo: " + message);
}
```

- Default max text message size: 64 KB
- Override: `gate.wsMaxMessageSize(128 * 1024)` (before `start()`)
- `ctx.send()` throws `UncheckedIOException` on `IOException`

### Error Handling

```java
gate.errorHandler((ctx, e) -> {
    ctx.status(500).json(Map.of("error", e.getMessage()));
});
```

Default: log + `500 Internal Server Error`. After-filter exceptions are logged and swallowed (do not reach error handler).

### Server Lifecycle

```java
GateServer server = gate.start(port);
server.join();    // block until stopped
server.stop();    // graceful stop
server.isRunning();
```

JVM shutdown hook to stop server is registered automatically.

### Database (HikariCP)

```java
Database.init(config.getDatabase());   // must call before gate.start()
try (Connection conn = Database.getConnection()) { ... }
Database.close();  // on shutdown
```

- MySQL JDBC URL: `jdbc:mysql://<host>:<port>/<name>?...`
- GCP Cloud SQL: when `cloudSqlInstance` is set, uses MySQL Cloud SQL Socket Factory; `host`/`port` ignored
- Schema from `src/main/resources/schema.sql` auto-executed on `Database.init()`; missing file logs warning and continues

---

## 12. Key Invariants and Behavioral Notes

1. **`TINYINT(1)` as boolean**: MySQL returns `TINYINT` columns as integers. `DataController` and `CongestionController` check `rs.getInt(...) == 1` for boolean columns. `AdminController.normalizeValue()` converts JSON booleans to `1`/`0` for DB writes.

2. **`INSERT IGNORE` semantics**: Seed data uses `INSERT IGNORE` — silently skips rows with duplicate PKs. Safe to run on populated tables.

3. **`ON DUPLICATE KEY UPDATE`**: `congestion_status` uses upsert semantics. Metrics flush also uses this pattern.

4. **Null handling in JSON**: `DataController` omits JSON keys for null values (using `putStringOrNull`/`putDoubleOrNull` helpers). `AdminController.putValue()` uses `node.putNull(col)` for explicit nulls in admin table views.

5. **Cache invalidation**: `DataController` cache has no manual invalidation. Admin data changes (via `AdminController`) are not reflected in `/events`, `/food`, `/map` until the 5-minute cache expires.

6. **`updated_by` in congestion**: Set to the CF Access verified email if available, else `"unknown"`. This means congestion updates from API-key-only clients (no CF Access JWT) are attributed to `"unknown"`.

7. **`execSql` multi-statement**: Splits on `;` and executes each statement in sequence. Returns result of the last non-empty statement. Any exception returns `400` with MySQL's error message directly (unlike other endpoints that return generic 503).

8. **Admin stats filtering**: `/admin/*` paths are excluded from endpoint metrics via `RequestMetrics.record()` and from the stats response in `AdminController.stats()`.

9. **`DataSeeder` migration gap**: Versions jump non-linearly. `if (v == 1)` triggers v1→v5 migration, then `if (v <= 1)` re-runs full seed. `if (v <= 3)` runs v3 migration even if current is v2 (to handle databases that skipped a version).

10. **Preflight OPTIONS handling**: Both `ApiKeyAuth` and `CfAccessAuth` skip `OPTIONS` requests. `CloudflareIpFilter` does NOT skip OPTIONS — Cloudflare IPs are still required for preflight. CORS preflight returns `204` after before-filters.

11. **Virtual threads**: Gate uses Java 21 virtual threads. Each HTTP request runs on its own virtual thread. Blocking DB calls (`Database.getConnection()`) are safe.

12. **Body size limit**: `ctx.body()` maximum is 1 MB.

13. **`getTable` row limit**: `SELECT * FROM ... LIMIT 500` — tables larger than 500 rows will be truncated in admin view.
