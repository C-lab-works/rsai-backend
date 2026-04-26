package dev.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.core.Database;
import dev.gate.core.Logger;
import dev.gate.mapping.DeleteMapping;
import dev.gate.mapping.GetMapping;
import dev.gate.mapping.PostMapping;
import dev.gate.mapping.PutMapping;

import java.sql.*;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@GateController
public class AdminController {

    private static final Logger logger = new Logger(AdminController.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @GetMapping("/admin/tables")
    public void listTables(Context ctx) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT TABLE_NAME, IFNULL(TABLE_ROWS, 0) AS TABLE_ROWS " +
                 "FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME")) {
            ArrayNode arr = mapper.createArrayNode();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ObjectNode n = arr.addObject();
                    n.put("name",     rs.getString("TABLE_NAME"));
                    n.put("rowCount", rs.getLong("TABLE_ROWS"));
                }
            }
            ctx.json(arr);
        } catch (Exception e) {
            logger.error("listTables error: {}", e.getMessage());
            ctx.status(503).json(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/admin/tables/{table}")
    public void getTable(Context ctx) {
        String table = ctx.pathParam("table");
        if (!isValidTableName(table, ctx)) return;
        try (Connection conn = Database.getConnection()) {
            ObjectNode root = mapper.createObjectNode();
            DatabaseMetaData meta = conn.getMetaData();

            Set<String> pks = new HashSet<>();
            try (ResultSet rs = meta.getPrimaryKeys(null, null, table)) {
                while (rs.next()) pks.add(rs.getString("COLUMN_NAME"));
            }

            ArrayNode cols = root.putArray("cols");
            try (ResultSet rs = meta.getColumns(null, null, table, null)) {
                while (rs.next()) {
                    ObjectNode col = cols.addObject();
                    String name = rs.getString("COLUMN_NAME");
                    col.put("name", name);
                    col.put("type", rs.getString("TYPE_NAME"));
                    if (pks.contains(name)) col.put("pk", true);
                }
            }

            ArrayNode rows = root.putArray("rows");
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT * FROM `" + table + "` LIMIT 500")) {
                ResultSetMetaData rsMeta = rs.getMetaData();
                int colCount = rsMeta.getColumnCount();
                while (rs.next()) {
                    ObjectNode row = rows.addObject();
                    for (int i = 1; i <= colCount; i++) {
                        putValue(row, rsMeta.getColumnName(i), rs.getObject(i));
                    }
                }
            }
            ctx.json(root);
        } catch (Exception e) {
            logger.error("getTable '{}' error: {}", table, e.getMessage());
            ctx.status(503).json(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/admin/tables/{table}/{pk}")
    public void updateRow(Context ctx) {
        String table = ctx.pathParam("table");
        String pkVal = ctx.pathParam("pk");
        if (!isValidTableName(table, ctx)) return;
        try (Connection conn = Database.getConnection()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAs(Map.class);
            String pkCol = getPkColumn(conn, table);
            if (pkCol == null) { ctx.status(400).json(Map.of("error", "No PK found")); return; }

            List<String> updateCols = body.keySet().stream()
                    .filter(k -> isValidIdentifier(k) && !k.equals(pkCol))
                    .collect(Collectors.toList());
            if (updateCols.isEmpty()) { ctx.status(400).json(Map.of("error", "No columns to update")); return; }

            String setClauses = updateCols.stream().map(c -> "`" + c + "` = ?").collect(Collectors.joining(", "));
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE `" + table + "` SET " + setClauses + " WHERE `" + pkCol + "` = ?")) {
                int i = 1;
                for (String col : updateCols) ps.setObject(i++, body.get(col));
                ps.setString(i, pkVal);
                ctx.json(Map.of("updated", ps.executeUpdate()));
            }
        } catch (Exception e) {
            logger.error("updateRow error: {}", e.getMessage());
            ctx.status(503).json(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/admin/tables/{table}/{pk}")
    public void deleteRow(Context ctx) {
        String table = ctx.pathParam("table");
        String pkVal = ctx.pathParam("pk");
        if (!isValidTableName(table, ctx)) return;
        try (Connection conn = Database.getConnection()) {
            String pkCol = getPkColumn(conn, table);
            if (pkCol == null) { ctx.status(400).json(Map.of("error", "No PK found")); return; }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM `" + table + "` WHERE `" + pkCol + "` = ?")) {
                ps.setString(1, pkVal);
                ctx.json(Map.of("deleted", ps.executeUpdate()));
            }
        } catch (Exception e) {
            logger.error("deleteRow error: {}", e.getMessage());
            ctx.status(503).json(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/admin/tables/{table}")
    public void insertRow(Context ctx) {
        String table = ctx.pathParam("table");
        if (!isValidTableName(table, ctx)) return;
        try (Connection conn = Database.getConnection()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAs(Map.class);
            List<String> insertCols = body.keySet().stream()
                    .filter(this::isValidIdentifier)
                    .collect(Collectors.toList());
            if (insertCols.isEmpty()) { ctx.status(400).json(Map.of("error", "No columns")); return; }

            String colList      = insertCols.stream().map(c -> "`" + c + "`").collect(Collectors.joining(", "));
            String placeholders = insertCols.stream().map(c -> "?").collect(Collectors.joining(", "));
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO `" + table + "` (" + colList + ") VALUES (" + placeholders + ")",
                    Statement.RETURN_GENERATED_KEYS)) {
                int i = 1;
                for (String col : insertCols) ps.setObject(i++, body.get(col));
                ps.executeUpdate();
                try (ResultSet gen = ps.getGeneratedKeys()) {
                    if (gen.next()) ctx.json(Map.of("id", gen.getLong(1)));
                    else ctx.json(Map.of("ok", true));
                }
            }
        } catch (Exception e) {
            logger.error("insertRow error: {}", e.getMessage());
            ctx.status(503).json(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/admin/sql")
    public void execSql(Context ctx) {
        try (Connection conn = Database.getConnection()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAs(Map.class);
            String sql = (String) body.get("sql");
            if (sql == null || sql.isBlank()) { ctx.status(400).json(Map.of("error", "sql required")); return; }

            ObjectNode lastResult = null;
            for (String raw : sql.split(";")) {
                String stmt = raw.strip();
                if (stmt.isEmpty()) continue;
                try (Statement s = conn.createStatement()) {
                    boolean hasRs = s.execute(stmt);
                    lastResult = mapper.createObjectNode();
                    ArrayNode colsNode = lastResult.putArray("cols");
                    ArrayNode rowsNode = lastResult.putArray("rows");
                    if (hasRs) {
                        try (ResultSet rs = s.getResultSet()) {
                            ResultSetMetaData meta = rs.getMetaData();
                            int colCount = meta.getColumnCount();
                            for (int i = 1; i <= colCount; i++) {
                                ObjectNode col = colsNode.addObject();
                                col.put("name", meta.getColumnName(i));
                                col.put("type", meta.getColumnTypeName(i).toLowerCase());
                            }
                            while (rs.next()) {
                                ObjectNode row = rowsNode.addObject();
                                for (int i = 1; i <= colCount; i++) {
                                    putValue(row, meta.getColumnName(i), rs.getObject(i));
                                }
                            }
                        }
                    } else {
                        lastResult.put("affected", s.getUpdateCount());
                    }
                }
            }
            ctx.json(lastResult != null ? lastResult : mapper.createObjectNode());
        } catch (Exception e) {
            logger.error("execSql error: {}", e.getMessage());
            ctx.status(400).json(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/admin/stats")
    public void stats(Context ctx) {
        RequestMetrics m = RequestMetrics.get();
        long[] perc = m.getPercentiles();

        ObjectNode root = mapper.createObjectNode();
        root.put("totalRequests", m.getTotalRequests());
        root.put("errorRate",     Math.round(m.getErrorRate() * 100.0) / 100.0);
        root.put("p50ms",         perc[0]);
        root.put("p95ms",         perc[1]);
        root.put("instances",     1);
        root.put("maxInstances",  10);

        ArrayNode chart = root.putArray("chart");
        for (long v : m.getHourlyCounts()) chart.add(v);

        ArrayNode endpoints = root.putArray("endpoints");
        for (var e : m.getTopEndpoints(10)) {
            String[] parts = e.getKey().split(" ", 2);
            addEndpoint(endpoints, parts[0], parts.length > 1 ? parts[1] : "", e.getValue());
        }

        ArrayNode system = root.putArray("system");
        addStatus(system, "Database",   "ok", "Connected");
        addStatus(system, "API Server", "ok", "Running");
        ctx.json(root);
    }

    // ── util ────────────────────────────────────────────────────────────────

    private void putValue(ObjectNode row, String col, Object val) {
        if (val == null)              { row.putNull(col); return; }
        if (val instanceof Long    v) { row.put(col, v); return; }
        if (val instanceof Integer v) { row.put(col, v); return; }
        if (val instanceof Double  v) { row.put(col, v); return; }
        if (val instanceof Float   v) { row.put(col, v); return; }
        if (val instanceof Boolean v) { row.put(col, v); return; }
        row.put(col, val.toString());
    }

    private void addEndpoint(ArrayNode arr, String method, String path, long count) {
        ObjectNode n = arr.addObject();
        n.put("method", method); n.put("path", path); n.put("count", count);
    }

    private void addStatus(ArrayNode arr, String name, String status, String value) {
        ObjectNode n = arr.addObject();
        n.put("name", name); n.put("status", status); n.put("value", value);
    }

    private boolean isValidTableName(String table, Context ctx) {
        if (!isValidIdentifier(table)) {
            ctx.status(400).json(Map.of("error", "Invalid table name"));
            return false;
        }
        return true;
    }

    private boolean isValidIdentifier(String s) {
        return s != null && s.matches("[a-zA-Z0-9_]+");
    }

    private String getPkColumn(Connection conn, String table) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getPrimaryKeys(null, null, table)) {
            if (rs.next()) return rs.getString("COLUMN_NAME");
        }
        return null;
    }
}
