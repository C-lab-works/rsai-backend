package app;

import dev.gate.core.Database;
import dev.gate.core.Logger;
import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.mapping.DeleteMapping;
import dev.gate.mapping.GetMapping;
import dev.gate.mapping.PostMapping;
import dev.gate.mapping.PutMapping;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@GateController
public class UserController {

    private static final Logger logger = new Logger(UserController.class);

    record User(int id, String name, String email) {}
    record CreateUserRequest(String name, String email) {}

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    @GetMapping("/users")
    public void getUsers(Context ctx) {
        int page = parseQueryInt(ctx.query("page"), 0);
        int size = Math.min(parseQueryInt(ctx.query("size"), DEFAULT_PAGE_SIZE), MAX_PAGE_SIZE);
        if (page < 0) page = 0;

        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, name, email FROM users ORDER BY id LIMIT ? OFFSET ?")) {
            ps.setInt(1, size);
            ps.setInt(2, page * size);
            try (ResultSet rs = ps.executeQuery()) {
                List<User> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(new User(rs.getInt("id"), rs.getString("name"), rs.getString("email")));
                }
                ctx.json(list);
            }
        } catch (SQLException e) {
            logger.error("getUsers failed", e);
            ctx.status(500).json(Map.of("error", "database error"));
        }
    }

    @GetMapping("/users/{id}")
    public void getUser(Context ctx) {
        int id = parseId(ctx);
        if (id <= 0) return;

        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id, name, email FROM users WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ctx.json(new User(rs.getInt("id"), rs.getString("name"), rs.getString("email")));
                } else {
                    ctx.status(404).json(Map.of("error", "user not found"));
                }
            }
        } catch (SQLException e) {
            logger.error("getUser failed for id={}", id, e);
            ctx.status(500).json(Map.of("error", "database error"));
        }
    }

    @PostMapping("/users")
    public void createUser(Context ctx) {
        if (!isJsonContentType(ctx)) {
            ctx.status(415).json(Map.of("error", "Content-Type must be application/json"));
            return;
        }
        CreateUserRequest req;
        try {
            req = ctx.bodyAs(CreateUserRequest.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "invalid request body"));
            return;
        }
        String validationError = validateRequest(req);
        if (validationError != null) {
            ctx.status(400).json(Map.of("error", validationError));
            return;
        }

        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO users (name, email) VALUES (?, ?) RETURNING id, name, email")) {
            ps.setString(1, req.name());
            ps.setString(2, req.email());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ctx.status(201).json(new User(rs.getInt("id"), rs.getString("name"), rs.getString("email")));
                } else {
                    logger.error("createUser: INSERT returned no rows");
                    ctx.status(500).json(Map.of("error", "database error"));
                }
            }
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                ctx.status(409).json(Map.of("error", "email already exists"));
            } else {
                logger.error("createUser failed", e);
                ctx.status(500).json(Map.of("error", "database error"));
            }
        }
    }

    @PutMapping("/users/{id}")
    public void updateUser(Context ctx) {
        int id = parseId(ctx);
        if (id <= 0) return;

        if (!isJsonContentType(ctx)) {
            ctx.status(415).json(Map.of("error", "Content-Type must be application/json"));
            return;
        }
        CreateUserRequest req;
        try {
            req = ctx.bodyAs(CreateUserRequest.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "invalid request body"));
            return;
        }
        String validationError = validateRequest(req);
        if (validationError != null) {
            ctx.status(400).json(Map.of("error", validationError));
            return;
        }

        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE users SET name = ?, email = ? WHERE id = ? RETURNING id, name, email")) {
            ps.setString(1, req.name());
            ps.setString(2, req.email());
            ps.setInt(3, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ctx.json(new User(rs.getInt("id"), rs.getString("name"), rs.getString("email")));
                } else {
                    ctx.status(404).json(Map.of("error", "user not found"));
                }
            }
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                ctx.status(409).json(Map.of("error", "email already exists"));
            } else {
                logger.error("updateUser failed for id={}", id, e);
                ctx.status(500).json(Map.of("error", "database error"));
            }
        }
    }

    @DeleteMapping("/users/{id}")
    public void deleteUser(Context ctx) {
        int id = parseId(ctx);
        if (id <= 0) return;

        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM users WHERE id = ?")) {
            ps.setInt(1, id);
            if (ps.executeUpdate() > 0) {
                ctx.status(204).result("");
            } else {
                ctx.status(404).json(Map.of("error", "user not found"));
            }
        } catch (SQLException e) {
            logger.error("deleteUser failed for id={}", id, e);
            ctx.status(500).json(Map.of("error", "database error"));
        }
    }

    private int parseId(Context ctx) {
        try {
            int id = Integer.parseInt(ctx.pathParam("id"));
            if (id <= 0) {
                ctx.status(400).json(Map.of("error", "id must be a positive number"));
                return -1;
            }
            return id;
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "id must be a number"));
            return -1;
        }
    }

    private String validateRequest(CreateUserRequest req) {
        if (req == null) return "name and email are required";
        if (req.name() == null || req.name().isBlank()) return "name is required";
        if (req.name().length() > 255) return "name must be 255 characters or fewer";
        if (req.email() == null || req.email().isBlank()) return "email is required";
        if (req.email().length() > 255) return "email must be 255 characters or fewer";
        if (!req.email().contains("@")) return "email is invalid";
        return null;
    }

    private int parseQueryInt(String value, int defaultValue) {
        if (value == null) return defaultValue;
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return defaultValue; }
    }

    private boolean isJsonContentType(Context ctx) {
        String ct = ctx.requestHeader("Content-Type");
        return ct != null && ct.contains("application/json");
    }
}
