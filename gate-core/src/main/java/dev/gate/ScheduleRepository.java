package dev.gate;

import dev.gate.core.Database;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ScheduleRepository {

    public List<Schedule> findAll() throws SQLException {
        List<Schedule> list = new ArrayList<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, title, start_at, end_at, created_at FROM schedules ORDER BY start_at");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(map(rs));
        }
        return list;
    }

    public Optional<Schedule> findById(long id) throws SQLException {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, title, start_at, end_at, created_at FROM schedules WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Schedule create(String title, String startAt, String endAt) throws SQLException {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO schedules (title, start_at, end_at) VALUES (?, ?, ?)",
                 Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, title);
            ps.setString(2, startAt);
            ps.setString(3, endAt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return findById(keys.getLong(1)).orElseThrow();
                }
            }
        }
        throw new SQLException("Failed to retrieve generated key");
    }

    public Optional<Schedule> update(long id, String title, String startAt, String endAt) throws SQLException {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE schedules SET title = ?, start_at = ?, end_at = ? WHERE id = ?")) {
            ps.setString(1, title);
            ps.setString(2, startAt);
            ps.setString(3, endAt);
            ps.setLong(4, id);
            int updated = ps.executeUpdate();
            return updated > 0 ? findById(id) : Optional.empty();
        }
    }

    public boolean delete(long id) throws SQLException {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM schedules WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    private Schedule map(ResultSet rs) throws SQLException {
        return new Schedule(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("start_at"),
            rs.getString("end_at"),
            rs.getString("created_at")
        );
    }
}
