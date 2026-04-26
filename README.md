# Gate

A lightweight HTTP framework for Java 21, built on Jetty with virtual thread support.

[Japanese README](README_ja.md)

## Table of Contents

- [Requirements](#requirements)
- [Quick Start](#quick-start)
- [Routing](#routing)
- [Context API](#context-api)
- [Middleware](#middleware)
- [WebSocket](#websocket)
- [CORS](#cors)
- [Error Handling](#error-handling)
- [Database](#database)
- [Configuration](#configuration)
- [Server Lifecycle](#server-lifecycle)
- [Build](#build)
- [License](#license)

## Requirements

- Java 21
- Gradle 9.x

## Quick Start

### 1. Create a controller

```java
import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.mapping.GetMapping;
import dev.gate.mapping.PostMapping;

@GateController
public class HelloController {

    @GetMapping("/hello")
    public void hello(Context ctx) {
        ctx.result("Hello, World!");
    }

    @GetMapping("/hello/{name}")
    public void helloName(Context ctx) {
        ctx.result("Hello, " + ctx.pathParam("name") + "!");
    }

    @PostMapping("/echo")
    public void echo(Context ctx) {
        ctx.json(ctx.bodyAs(MyRequest.class));
    }
}
```

### 2. Start the server

```java
import dev.gate.core.*;

public class Main {
    public static void main(String[] args) throws Exception {
        Config config = ConfigLoader.load();
        Database.init(config.getDatabase());

        Gate gate = new Gate();
        gate.register(new HelloController());

        GateServer server = gate.start(config.getPort());

        Runtime.getRuntime().addShutdownHook(new Thread(Database::close));
        server.join();
    }
}
```

## Routing

### Annotation-based

```java
@GateController
public class UserController {

    @GetMapping("/users")
    public void list(Context ctx) { ... }

    @GetMapping("/users/{id}")
    public void get(Context ctx) { ... }

    @PostMapping("/users")
    public void create(Context ctx) { ... }

    @PutMapping("/users/{id}")
    public void update(Context ctx) { ... }

    @DeleteMapping("/users/{id}")
    public void delete(Context ctx) { ... }

    @PatchMapping("/users/{id}/status")
    public void patch(Context ctx) { ... }
}
```

Register an instance manually:

```java
gate.register(new UserController());
```

Or scan a package to auto-register all `@GateController` classes from the classpath:

```java
gate.scan("com.example.controllers");
```

### Programmatic

```java
Gate gate = new Gate();

gate.get("/ping", ctx -> ctx.result("pong"));
gate.post("/data", ctx -> ctx.json(ctx.bodyAs(Data.class)));
```

### Path parameters

Use curly-brace syntax. Parameters are URL-decoded (UTF-8) before delivery.

```java
@GetMapping("/users/{id}")
public void get(Context ctx) {
    String id = ctx.pathParam("id");
}
```

- Empty param names (`{}`) and duplicate names in the same pattern throw `IllegalArgumentException` at registration time.
- Exact routes always take priority over pattern routes.
- Within pattern routes, the first-registered match wins.
- Trailing slashes are stripped before matching: `/users/` resolves to `/users`.

## Context API

### Request

| Method | Return | Description |
|---|---|---|
| `ctx.path()` | `String` | Normalized request path |
| `ctx.method()` | `String` | HTTP method, e.g. `"GET"` |
| `ctx.pathParam("name")` | `String` or `null` | URL path parameter by name |
| `ctx.query("key")` | `String` or `null` | Query string parameter |
| `ctx.requestHeader("name")` | `String` or `null` | Request header value |
| `ctx.body()` | `String` | Full request body as string, cached after first read. Maximum: 1 MB. |
| `ctx.bodyAs(MyClass.class)` | `T` or `null` | Deserializes JSON body via Jackson. Returns `null` if body is empty. |

### Response

All response methods return `this` for chaining.

| Method | Description |
|---|---|
| `ctx.result("text")` | Plain text response (`text/plain; charset=utf-8`) |
| `ctx.json(object)` | JSON response via Jackson (`application/json; charset=utf-8`) |
| `ctx.status(201)` | Set HTTP status code (default: `200`) |
| `ctx.header("X-Key", "val")` | Set response header. Throws `IllegalArgumentException` if key or value contains `\r` or `\n`. |

```java
ctx.status(404).json(Map.of("error", "not found"));
```

## Middleware

```java
gate.before(ctx -> {
    String token = ctx.requestHeader("Authorization");
    if (token == null) {
        ctx.status(401).result("Unauthorized");
        throw new RuntimeException("unauthorized");
    }
});

gate.after(ctx -> {
    // logging, metrics, cleanup
});
```

Request execution order:

```
before filters (in order) -> route handler -> after filters (in order) -> write response
```

Note: Setting `ctx.status(401)` in a `before` filter does **not** stop routing on its own.
You must throw an exception to prevent the route handler from executing.
The thrown exception is passed to the error handler. To avoid the default 500 response,
register a custom error handler that inspects the exception type.

Calling `ctx.halt()` in a `before` filter stops routing without an exception. The route handler
is skipped, and execution jumps to after filters. Note that `halt()` also stops any remaining
before filters from running — only the filters registered before the halting one will have executed.

After filters always run, even when a before filter or the route handler threw an exception.
Each after filter runs in its own try/catch, so one failure does not stop the rest.
The response is written after all after filters complete, so they may still modify it.

## WebSocket

```java
@GateController
public class ChatController {

    @WsMapping("/chat")
    public void chat(WsContext ctx, String message) {
        if (ctx.isOpen()) {
            ctx.send("Echo: " + message);
        }
    }
}
```

`WsContext` API:

| Method | Description |
|---|---|
| `ctx.send(String)` | Send a text frame. Throws `UncheckedIOException` if an `IOException` occurs. |
| `ctx.isOpen()` | Returns `true` if the connection is still open. |

Always call `ctx.isOpen()` before `ctx.send()`. If the session was closed by the remote end,
Jetty may throw an exception that is not an `IOException`, which would propagate unwrapped
from `send()` rather than as `UncheckedIOException`.

Default max text message size is 64 KB. Override before calling `start()`:

```java
gate.wsMaxMessageSize(128 * 1024);
```

## CORS

```java
gate.cors("https://example.com");  // specific origin
gate.cors("*");                     // wildcard (credentials not supported)
```

Sets the following headers on every response:

```
Access-Control-Allow-Origin: <value>
Access-Control-Allow-Methods: GET, POST, PUT, DELETE, PATCH, OPTIONS
Access-Control-Allow-Headers: Content-Type, Authorization, X-API-Key
Access-Control-Max-Age: 86400
```

`Access-Control-Allow-Credentials: true` is added only when the origin is not `"*"`.

Preflight `OPTIONS` requests return `204` after before filters run. This allows IP-based filters (e.g. `CloudflareIpFilter`) to reject unauthorized origins before responding. Filters that require request credentials (e.g. `ApiKeyAuth`, `CfAccessAuth`) must skip OPTIONS internally, since browsers do not include credentials in preflights.

## Error Handling

```java
gate.errorHandler((ctx, e) -> {
    ctx.status(500).json(Map.of("error", e.getMessage()));
});
```

The default handler logs the exception and returns `500 Internal Server Error`.
Exceptions thrown inside after filters are logged and swallowed — they do not reach the error handler.

If the error handler itself throws an exception, that exception is not caught. After filters still
run via `finally`, but the response may be incomplete. Keep error handlers simple and exception-free.

## Database

Gate includes built-in PostgreSQL support via HikariCP.

```java
// Initialize on startup (must be called before gate.start())
Database.init(config.getDatabase());

// Use in handlers
try (Connection conn = Database.getConnection();
     PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE id = ?")) {
    ps.setInt(1, id);
    ResultSet rs = ps.executeQuery();
    ...
}

// Close on shutdown
Database.close();
```

### Schema initialization

Place `schema.sql` in `src/main/resources`. It runs automatically on `Database.init()`.
Use `IF NOT EXISTS` to keep it idempotent across restarts.

```sql
CREATE TABLE IF NOT EXISTS users (
    id         SERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    email      VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
```

If `schema.sql` is not found, a warning is logged and startup continues normally.

### Cloud SQL (GCP)

```yaml
database:
  cloudSqlInstance: "my-project:us-central1:my-instance"
  name: mydb
  user: myuser
  password: ""
```

When `cloudSqlInstance` is non-blank, the Cloud SQL Socket Factory is used and `host`/`port` are ignored.
Can also be set via the `CLOUD_SQL_INSTANCE` environment variable.

## Configuration

`src/main/resources/config.yml`:

```yaml
port: 8080
env: development
name: MyApp
database:
  host: localhost
  port: 5432
  name: mydb
  user: postgres
  password: ""
  cloudSqlInstance: ""   # GCP Cloud SQL instance (project:region:instance)
  maxPoolSize: 10
```

If `config.yml` is absent or fails to parse, default values are used and a warning is logged.

All database fields can be overridden with environment variables:

| Environment variable | Config field |
|---|---|
| `DB_HOST` | `database.host` |
| `DB_PORT` | `database.port` |
| `DB_NAME` | `database.name` |
| `DB_USER` | `database.user` |
| `DB_PASSWORD` | `database.password` |
| `CLOUD_SQL_INSTANCE` | `database.cloudSqlInstance` |

## Server Lifecycle

`gate.start(port)` returns a `GateServer` instance.

| Method | Description |
|---|---|
| `server.join()` | Blocks until the server stops. Call at the end of `main`. |
| `server.stop()` | Gracefully stops the server. |
| `server.isRunning()` | Returns `true` if the server is currently running. |

A JVM shutdown hook to stop the server is registered automatically.
Register separate hooks for other cleanup such as `Database.close()`.

## Build

```bash
./gradlew build
```

## License

MIT License

Copyright (c) 2026 tatsu-t

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
