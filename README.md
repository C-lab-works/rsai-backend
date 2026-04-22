# Gate

A lightweight HTTP framework for Java 21, built on Jetty with virtual thread support.

## Requirements

- Java 21
- Gradle 9.x

## Modules

| Module | Description |
|---|---|
| `gate-mapping` | HTTP verb annotations (`@GetMapping`, `@PostMapping`, etc.) |
| `gate-core` | Framework core: routing, request/response, WebSocket, database |

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
}
```

### Programmatic

```java
Gate gate = new Gate();

gate.get("/ping", ctx -> ctx.result("pong"));
gate.post("/data", ctx -> ctx.json(ctx.bodyAs(Data.class)));
```

## Context API

```java
// Request
ctx.pathParam("id");         // path parameter
ctx.query("page");           // query parameter
ctx.body();                  // raw request body
ctx.bodyAs(MyClass.class);   // deserialize JSON body
ctx.requestHeader("Authorization");
ctx.method();                // HTTP method
ctx.path();                  // request path

// Response
ctx.result("text");          // plain text response
ctx.json(object);            // JSON response (sets Content-Type automatically)
ctx.status(201);             // status code
ctx.header("X-Key", "val"); // response header

// Chaining
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

## WebSocket

```java
@GateController
public class ChatController {

    @WsMapping("/chat")
    public void chat(WsContext ctx, String message) {
        ctx.send("Echo: " + message);
    }
}
```

```java
// WsContext API
ctx.send("message");
ctx.isOpen();
```

## CORS

```java
gate.cors("https://example.com");  // specific origin
gate.cors("*");                     // wildcard (credentials not supported)
```

## Error Handling

```java
gate.errorHandler((ctx, e) -> {
    ctx.status(500).json(Map.of("error", e.getMessage()));
});
```

## Database

Gate includes built-in PostgreSQL support via HikariCP.

```java
// Initialize on startup
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

Place a `schema.sql` file in `src/main/resources`. It is executed automatically on startup.

```sql
CREATE TABLE IF NOT EXISTS users (
    id         SERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    email      VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
```

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
  maxPoolSize: 33
```

All database fields can be overridden with environment variables:

| Environment variable | Config field |
|---|---|
| `DB_HOST` | `database.host` |
| `DB_PORT` | `database.port` |
| `DB_NAME` | `database.name` |
| `DB_USER` | `database.user` |
| `DB_PASSWORD` | `database.password` |
| `CLOUD_SQL_INSTANCE` | `database.cloudSqlInstance` |

## Build

```bash
./gradlew build
```

## License

MIT
