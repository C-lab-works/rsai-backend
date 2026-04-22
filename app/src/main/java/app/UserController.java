package app;

import app.db.Database;
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

    record User(int id, String name, String email) {}
    record CreateUserRequest(String name, String email) {}

    @GetMapping("/users")
    public void getUsers(Context ctx) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id, name, email FROM users ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            List<User> list = new ArrayList<>();
            while (rs.next()) {
                list.add(new User(rs.getInt("id"), rs.getString("name"), rs.getString("email")));
            }
            ctx.json(list);
        } catch (SQLException e) {
            ctx.status(500).json(Map.of("error", "database error"));
        }
    }

    @GetMapping("/users/{id}")
    public void getUser(Context ctx) {
        int id = parseId(ctx);
        if (id < 0) return;

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
            ctx.status(500).json(Map.of("error", "database error"));
        }
    }

    @PostMapping("/users")
    public void createUser(Context ctx) {
        CreateUserRequest req;
        try {
            req = ctx.bodyAs(CreateUserRequest.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "invalid request body"));
            return;
        }
        if (req == null || req.name() == null || req.email() == null) {
            ctx.status(400).json(Map.of("error", "name and email are required"));
            return;
        }

        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO users (name, email) VALUES (?, ?) RETURNING id, name, email")) {
            ps.setString(1, req.name());
            ps.setString(2, req.email());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                ctx.status(201).json(new User(rs.getInt("id"), rs.getString("name"), rs.getString("email")));
            }
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                ctx.status(409).json(Map.of("error", "email already exists"));
            } else {
                ctx.status(500).json(Map.of("error", "database error"));
            }
        }
    }

    @PutMapping("/users/{id}")
    public void updateUser(Context ctx) {
        int id = parseId(ctx);
        if (id < 0) return;

        CreateUserRequest req;
        try {
            req = ctx.bodyAs(CreateUserRequest.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "invalid request body"));
            return;
        }
        if (req == null || req.name() == null || req.email() == null) {
            ctx.status(400).json(Map.of("error", "name and email are required"));
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
                ctx.status(500).json(Map.of("error", "database error"));
            }
        }
    }

    @DeleteMapping("/users/{id}")
    public void deleteUser(Context ctx) {
        int id = parseId(ctx);
        if (id < 0) return;

        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM users WHERE id = ?")) {
            ps.setInt(1, id);
            if (ps.executeUpdate() > 0) {
                ctx.status(204).result("");
            } else {
                ctx.status(404).json(Map.of("error", "user not found"));
            }
        } catch (SQLException e) {
            ctx.status(500).json(Map.of("error", "database error"));
        }
    }

    private int parseId(Context ctx) {
        try {
            return Integer.parseInt(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "id must be a number"));
            return -1;
        }
    }
}
