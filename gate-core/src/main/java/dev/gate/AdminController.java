package dev.gate;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
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
// /admin 用エンドポイント　管理者専用
@GateController
public class AdminController {

    private static final Logger       logger              = new Logger(AdminController.class);
    private static final ObjectMapper mapper              = new ObjectMapper();
    private static final HttpClient   http                = HttpClient.newHttpClient();
    private static final String       GCP_METADATA_BASE   = "http://metadata.google.internal/computeMetadata/v1/";
    private static final String       GCP_MONITORING_BASE = "https://monitoring.googleapis.com/v3/projects/";
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[a-zA-Z0-9_]+");
    private static final Pattern DEFAULT_VALUE_PATTERN = Pattern.compile("[a-zA-Z0-9._\\-]+");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    // SQLコマンドホワイトリスト
    private static final Set<String> ALLOWED_SQL_KEYWORDS = Set.of(
        "SELECT", "INSERT", "UPDATE", "DELETE",
        "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "ANALYZE",
        "ALTER"
    );

    private static final int TOP_ENDPOINTS_COUNT = 10;

    private static final Set<String> ALLOWED_COL_TYPES = Set.of(
        "INT", "BIGINT", "VARCHAR(255)", "VARCHAR(100)", "TEXT",
        "TINYINT(1)", "FLOAT", "DOUBLE", "DATE", "DATETIME", "TIME"
    );

    // インスタンス一覧を返す
    @GetMapping("/admin/instances")
    public void listInstances(Context ctx) {
        ctx.header("Cache-Control", "no-store");
        try {
            ArrayNode arr = mapper.createArrayNode();
            for (FirestoreRest.Entry entry : FirestoreRest.get().list("instances")) {
                ObjectNode n = arr.addObject();
                n.put("instanceId", entry.id());
                putStringField(n, "revision",  (String) entry.data().get("revision"));
                putStringField(n, "host",      (String) entry.data().get("host"));
                putStringField(n, "startedAt", (String) entry.data().get("startedAt"));
                putStringField(n, "status",    (String) entry.data().get("status"));
            }
            ctx.json(arr);
        } catch (Exception e) {
            logger.error("listInstances error", e);
            ctx.status(503).json(Map.of("error", "Firestore unavailable"));
        }
    }

    private static void putStringField(ObjectNode n, String key, String value) {
        if (value != null) n.put(key, value); else n.putNull(key);
    }

    // インスタンスにコマンドを送信し、最大5秒ポーリングしてレスポンスを返す
    @PostMapping("/admin/instances/{id}/command")
    @SuppressWarnings("unchecked")
    public void sendCommand(Context ctx) {
        String instanceId = ctx.pathParam("id");
        try {
            Map<String, Object> body = ctx.bodyAs(Map.class);
            if (body == null || !body.containsKey("type")) {
                ctx.status(400).json(Map.of("error", "type is required"));
                return;
            }
            String type       = (String) body.get("type");
            Object payloadRaw = body.get("payload");

            String requestId = UUID.randomUUID().toString();
            FirestoreRest fs = FirestoreRest.get();

            Map<String, Object> cmd = new java.util.HashMap<>();
            cmd.put("type",      type);
            cmd.put("requestId", requestId);
            cmd.put("issuedAt",  Instant.now().toString());
            if (payloadRaw != null) cmd.put("payload", payloadRaw);
            fs.update("instances/" + instanceId, Map.of("cmd", cmd));

            // poll for result (500ms × 10 = 5s max)
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(500);
                Map<String, Object> doc = fs.get("instances/" + instanceId);
                if (doc == null) break;
                Map<String, Object> res = (Map<String, Object>) doc.get("res");
                if (res != null && requestId.equals(res.get("requestId"))) {
                    ctx.json(res);
                    return;
                }
            }
            ctx.status(504).json(Map.of("error", "command timed out", "requestId", requestId));
        } catch (Exception e) {
            logger.error("sendCommand error instanceId={}", instanceId, e);
            ctx.status(503).json(Map.of("error", "Firestore unavailable"));
        }
    }

    // インスタンスのメトリクス履歴を返す（降順 → フロントで昇順に並べ直す）
    @GetMapping("/admin/instances/{id}/metrics")
    public void getInstanceMetrics(Context ctx) {
        ctx.header("Cache-Control", "no-store");
        String instanceId = ctx.pathParam("id");
        int limit = 40;
        try { limit = Math.min(200, Integer.parseInt(ctx.query("limit"))); } catch (Exception ignored) {}
        try {
            ArrayNode arr = mapper.createArrayNode();
            for (FirestoreRest.Entry entry :
                    FirestoreRest.get().query("instances/" + instanceId, "metrics", "t", true, limit)) {
                Map<String, Object> d = entry.data();
                ObjectNode n = arr.addObject();
                n.put("t",            toLong(d.get("t")));
                n.put("cpu",          toDouble(d.get("cpu")));
                n.put("heap_used_mb", toLong(d.get("heap_used_mb")));
                n.put("threads",      (int) toLong(d.get("threads")));
            }
            ctx.json(arr);
        } catch (Exception e) {
            logger.error("getInstanceMetrics error instanceId={}", instanceId, e);
            ctx.status(503).json(Map.of("error", "Firestore unavailable"));
        }
    }

    private static long   toLong(Object v)   { return v instanceof Long l ? l : v instanceof Number n ? n.longValue() : 0L; }
    private static double toDouble(Object v) { return v instanceof Double d ? d : v instanceof Number n ? n.doubleValue() : 0.0; }

    // 管理パネルからキャッシュを削除するエンドポイント
    // 即時ポーリングさせてキャッシュを更新させる
    @PostMapping("/admin/cache/clear")
    public void clearCache(Context ctx) {
        try {
            new DataController().refreshAll();
            AnnouncementsController.refreshCache();
            CongestionController.refreshCache();

            String clearedBy = ctx.getAttribute(CfAccessAuth.ATTR_VERIFIED_EMAIL);
            logger.info("cache refreshed by=" + clearedBy);

            InstanceManager.get().broadcastCacheRefresh();

            boolean cfPurged = purgeCfCache();

            ObjectNode res = mapper.createObjectNode();
            res.put("ok", true);
            res.put("cf_cache_purged", cfPurged);
            ArrayNode cleared = res.putArray("refreshed");
            for (String key : new String[]{"events", "food", "map", "announcements", "congestion"}) {
                cleared.add(key);
            }
            ctx.json(res);
        } catch (Exception e) {
            logger.error("clearCache error", e);
            ctx.status(500).json(Map.of("error", "Cache refresh failed: " + e.getMessage()));
        }
    }

    private boolean purgeCfCache() {
        String apiToken = System.getenv("CF_API_TOKEN");
        String zoneId   = System.getenv("CF_ZONE_ID");
        if (apiToken == null || apiToken.isBlank() || zoneId == null || zoneId.isBlank()) {
            logger.warn("purgeCfCache skipped: CF_API_TOKEN or CF_ZONE_ID not configured");
            return false;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.cloudflare.com/client/v4/zones/" + zoneId + "/purge_cache"))
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"purge_everything\":true}"))
                .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                logger.info("CF cache purged successfully");
                return true;
            }
            logger.warn("CF cache purge returned HTTP {}: {}", res.statusCode(),
                res.body().substring(0, Math.min(200, res.body().length())));
            return false;
        } catch (Exception e) {
            logger.warn("CF cache purge failed: {}", e.getMessage());
            return false;
        }
    }

    // 管理者パネルから意図的に503エラーを発生させるエンドポイント
    @GetMapping("/admin/debug/503")
    public void debug503(Context ctx) {
        String instanceId = Optional.ofNullable(System.getenv("HOSTNAME")).orElse("local");
        ctx.status(503).json(Map.of("error", "Debug: intentional 503 (instance: " + instanceId + ")"));
    }

    //管理者パネルのdbページでテーブル一覧を取得するエンドポイント
    @GetMapping("/admin/tables")
    public void listTables(Context ctx) {
        ctx.header("Cache-Control", "no-store");
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT TABLE_NAME, IFNULL(TABLE_ROWS, 0) AS TABLE_ROWS " +
                 "FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME")) {
            ArrayNode arr = mapper.createArrayNode();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ObjectNode n = arr.addObject();
                    n.put("name",      rs.getString("TABLE_NAME"));
                    n.put("row_count", rs.getLong("TABLE_ROWS"));
                }
            }
            ctx.json(arr);
        } catch (Exception e) {
            logger.error("listTables error", e);
            ctx.status(503).json(Map.of("error", "Service temporarily unavailable"));
        }
    }

    // 管理者パネルのdbページでテーブルの内容を取得するエンドポイント
    @GetMapping("/admin/tables/{table}")
    public void getTable(Context ctx) {
        String table = ctx.pathParam("table");
        if (!isValidTableName(table, ctx)) return;
        try (Connection conn = Database.getConnection()) {
            String resolvedTable = resolveTableName(conn, table);
            if (resolvedTable == null) { ctx.status(404).json(Map.of("error", "テーブルが見つかりません")); return; }

            ObjectNode root = mapper.createObjectNode();
            DatabaseMetaData meta = conn.getMetaData();

            Set<String> pks = new HashSet<>();
            try (ResultSet rs = meta.getPrimaryKeys(null, null, resolvedTable)) {
                while (rs.next()) pks.add(rs.getString("COLUMN_NAME"));
            }

            ArrayNode cols = root.putArray("cols");
            try (ResultSet rs = meta.getColumns(null, null, resolvedTable, null)) {
                while (rs.next()) {
                    ObjectNode col = cols.addObject();
                    String name = rs.getString("COLUMN_NAME");
                    col.put("name", name);
                    col.put("type", normalizeColumnType(
                        rs.getString("TYPE_NAME"),
                        rs.getInt("COLUMN_SIZE")
                    ));
                    if (pks.contains(name)) col.put("pk", true);
                }
            }

            ArrayNode rows = root.putArray("rows");
            String sort  = ctx.query("sort");
            String pkCol = getPkColumn(conn, resolvedTable);
            String order = ("desc".equalsIgnoreCase(sort) && pkCol != null)
                ? " ORDER BY `" + pkCol + "` DESC" : "";
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT * FROM `" + resolvedTable + "`" + order + " LIMIT 500")) {
                ResultSetMetaData rsMeta = rs.getMetaData();
                int colCount = rsMeta.getColumnCount();
                while (rs.next()) {
                    ObjectNode row = rows.addObject();
                    for (int i = 1; i <= colCount; i++) {
                        putValue(row, rsMeta.getColumnName(i), getColumnValue(rs, rsMeta, i));
                    }
                }
            }
            ctx.json(root);
        } catch (Exception e) {
            logger.error("getTable '{}' error", table, e);
            ctx.status(503).json(Map.of("error", "Service temporarily unavailable"));
        }
    }

    // 管理者パネルのdbページでテーブルの行を更新するエンドポイント
    @PutMapping("/admin/tables/{table}/{pk}")
    public void updateRow(Context ctx) {
        String table = ctx.pathParam("table");
        String pkVal = ctx.pathParam("pk");
        if (!isValidTableName(table, ctx)) return;
        try (Connection conn = Database.getConnection()) {
            String resolvedTable = resolveTableName(conn, table);
            if (resolvedTable == null) { ctx.status(404).json(Map.of("error", "テーブルが見つかりません")); return; }

            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAs(Map.class);
            if (body == null) { ctx.status(400).json(Map.of("error", "リクエストボディが必要です")); return; }
            String pkCol = getPkColumn(conn, resolvedTable);
            if (pkCol == null) { ctx.status(400).json(Map.of("error", "主キーが見つかりません")); return; }

            List<String> updateCols = getColumnNames(conn, resolvedTable).stream()
                    .filter(c -> body.containsKey(c) && !c.equals(pkCol))
                    .collect(Collectors.toList());
            if (updateCols.isEmpty()) { ctx.status(400).json(Map.of("error", "更新するカラムがありません")); return; }

            String setClauses = updateCols.stream().map(c -> "`" + c + "` = ?").collect(Collectors.joining(", "));
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE `" + resolvedTable + "` SET " + setClauses + " WHERE `" + pkCol + "` = ?")) {
                int i = 1;
                for (String col : updateCols) ps.setObject(i++, normalizeValue(body.get(col)));
                ps.setString(i, pkVal);
                ctx.json(Map.of("updated", ps.executeUpdate()));
            }
        } catch (SQLIntegrityConstraintViolationException e) {
            logger.warn("updateRow constraint violation: {}", e.getMessage());
            ctx.status(400).json(Map.of("error", toUserMessage(e)));
        } catch (SQLSyntaxErrorException e) {
            logger.warn("updateRow syntax error: {}", e.getMessage());
            ctx.status(400).json(Map.of("error", "SQL構文エラー"));
        } catch (SQLException e) {
            if (isDataTypeError(e)) {
                logger.warn("updateRow data type error: {}", e.getMessage());
                ctx.status(400).json(Map.of("error", toDataTypeMessage()));
            } else {
                logger.error("updateRow error", e);
                ctx.status(503).json(Map.of("error", "Service temporarily unavailable"));
            }
        } catch (Exception e) {
            logger.error("updateRow error (non-SQL)", e);
            ctx.status(503).json(Map.of("error", "Service temporarily unavailable"));
        }
    }

    // 管理者パネルのdbページでテーブルの行を削除するエンドポイント
    @DeleteMapping("/admin/tables/{table}/{pk}")
    public void deleteRow(Context ctx) {
        String table = ctx.pathParam("table");
        String pkVal = ctx.pathParam("pk");
        if (!isValidTableName(table, ctx)) return;
        try (Connection conn = Database.getConnection()) {
            String resolvedTable = resolveTableName(conn, table);
            if (resolvedTable == null) { ctx.status(404).json(Map.of("error", "テーブルが見つかりません")); return; }

            String pkCol = getPkColumn(conn, resolvedTable);
            if (pkCol == null) { ctx.status(400).json(Map.of("error", "主キーが見つかりません")); return; }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM `" + resolvedTable + "` WHERE `" + pkCol + "` = ?")) {
                ps.setString(1, pkVal);
                ctx.json(Map.of("deleted", ps.executeUpdate()));
            }
        } catch (SQLIntegrityConstraintViolationException e) {
            logger.warn("deleteRow constraint violation: {}", e.getMessage());
            ctx.status(400).json(Map.of("error", toUserMessage(e)));
        } catch (Exception e) {
            logger.error("deleteRow error", e);
            ctx.status(503).json(Map.of("error", "Service temporarily unavailable"));
        }
    }
    // 管理者パネルのdbページでテーブルの行を追加するエンドポイント
    @PostMapping("/admin/tables/{table}")
    public void insertRow(Context ctx) {
        String table = ctx.pathParam("table");
        if (!isValidTableName(table, ctx)) return;
        try (Connection conn = Database.getConnection()) {
            String resolvedTable = resolveTableName(conn, table);
            if (resolvedTable == null) { ctx.status(404).json(Map.of("error", "テーブルが見つかりません")); return; }

            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAs(Map.class);
            if (body == null) { ctx.status(400).json(Map.of("error", "リクエストボディが必要です")); return; }

            List<String> insertCols = getColumnNames(conn, resolvedTable).stream()
                    .filter(body::containsKey)
                    .collect(Collectors.toList());
            if (insertCols.isEmpty()) { ctx.status(400).json(Map.of("error", "カラムがありません")); return; }

            String colList      = insertCols.stream().map(c -> "`" + c + "`").collect(Collectors.joining(", "));
            String placeholders = insertCols.stream().map(c -> "?").collect(Collectors.joining(", "));
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO `" + resolvedTable + "` (" + colList + ") VALUES (" + placeholders + ")",
                    Statement.RETURN_GENERATED_KEYS)) {
                int i = 1;
                for (String col : insertCols) ps.setObject(i++, normalizeValue(body.get(col)));
                ps.executeUpdate();
                try (ResultSet gen = ps.getGeneratedKeys()) {
                    if (gen.next()) ctx.json(Map.of("id", gen.getLong(1)));
                    else ctx.json(Map.of("ok", true));
                }
            }
        } catch (SQLIntegrityConstraintViolationException e) {
            logger.warn("insertRow constraint violation: {}", e.getMessage());
            ctx.status(400).json(Map.of("error", toUserMessage(e)));
        } catch (SQLSyntaxErrorException e) {
            logger.warn("insertRow syntax error: {}", e.getMessage());
            ctx.status(400).json(Map.of("error", "SQL構文エラー"));
        } catch (SQLException e) {
            if (isDataTypeError(e)) {
                logger.warn("insertRow data type error: {}", e.getMessage());
                ctx.status(400).json(Map.of("error", toDataTypeMessage()));
            } else {
                logger.error("insertRow error", e);
                ctx.status(503).json(Map.of("error", "Service temporarily unavailable"));
            }
        } catch (Exception e) {
            logger.error("insertRow error (non-SQL)", e);
            ctx.status(503).json(Map.of("error", "Service temporarily unavailable"));
        }
    }

    // 管理者パネルのdbページでテーブルを作成するエンドポイント
    @PostMapping("/admin/ddl/tables")
    public void createTable(Context ctx) {
        try (Connection conn = Database.getConnection()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAs(Map.class);
            if (body == null) { ctx.status(400).json(Map.of("error", "リクエストボディが必要です")); return; }
            String tableName = (String) body.get("name");
            if (!isValidIdentifier(tableName)) {
                ctx.status(400).json(Map.of("error", "テーブル名が無効です")); return;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> columns = (List<Map<String, Object>>) body.get("columns");
            if (columns == null || columns.isEmpty()) {
                ctx.status(400).json(Map.of("error", "カラムの定義が必要です")); return;
            }

            StringBuilder sb = new StringBuilder("CREATE TABLE `").append(tableName).append("` (");
            List<String> colDefs = new ArrayList<>();
            for (Map<String, Object> col : columns) {
                String name = (String) col.get("name");
                String type = (String) col.get("type");
                if (!isValidIdentifier(name)) {
                    ctx.status(400).json(Map.of("error", "カラム名が無効です: " + name)); return;
                }
                if (type == null || !ALLOWED_COL_TYPES.contains(type)) {
                    ctx.status(400).json(Map.of("error", "サポートされていない型: " + type)); return;
                }
                StringBuilder colDef = new StringBuilder("`").append(name).append("` ").append(type);
                if (Boolean.TRUE.equals(col.get("notNull")))       colDef.append(" NOT NULL");
                if (Boolean.TRUE.equals(col.get("autoIncrement"))) colDef.append(" AUTO_INCREMENT");
                if (Boolean.TRUE.equals(col.get("pk")))            colDef.append(" PRIMARY KEY");
                colDefs.add(colDef.toString());
            }
            sb.append(String.join(", ", colDefs)).append(")");

            try (Statement s = conn.createStatement()) { s.execute(sb.toString()); }
            ctx.json(Map.of("ok", true));
        } catch (SQLSyntaxErrorException e) {
            logger.warn("createTable syntax error: {}", e.getMessage());
            ctx.status(400).json(Map.of("error", "テーブル作成に失敗しました"));
        } catch (Exception e) {
            logger.error("createTable error", e);
            ctx.status(503).json(Map.of("error", "Service temporarily unavailable"));
        }
    }

    // 管理者パネルのdbページでテーブルにカラムを追加するエンドポイント
    @PostMapping("/admin/ddl/tables/{table}/columns")
    public void addColumn(Context ctx) {
        String table = ctx.pathParam("table");
        if (!isValidTableName(table, ctx)) return;
        try (Connection conn = Database.getConnection()) {
            String resolvedTable = resolveTableName(conn, table);
            if (resolvedTable == null) { ctx.status(404).json(Map.of("error", "テーブルが見つかりません")); return; }

            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAs(Map.class);
            if (body == null) { ctx.status(400).json(Map.of("error", "リクエストボディが必要です")); return; }
            String colName    = (String) body.get("name");
            String colType    = (String) body.get("type");
            boolean notNull   = Boolean.TRUE.equals(body.get("notNull"));
            String defaultVal = body.get("defaultValue") instanceof String s ? s.strip() : null;

            if (!isValidIdentifier(colName)) {
                ctx.status(400).json(Map.of("error", "カラム名が無効です")); return;
            }
            if (colType == null || !ALLOWED_COL_TYPES.contains(colType)) {
                ctx.status(400).json(Map.of("error", "サポートされていない型: " + colType)); return;
            }

            StringBuilder sb = new StringBuilder("ALTER TABLE `")
                .append(resolvedTable).append("` ADD COLUMN `")
                .append(colName).append("` ").append(colType);
            if (notNull) sb.append(" NOT NULL");
            if (defaultVal != null && !defaultVal.isEmpty()) {
                if (!DEFAULT_VALUE_PATTERN.matcher(defaultVal).matches()) {
                    ctx.status(400).json(Map.of("error", "デフォルト値に使えない文字が含まれています")); return;
                }
                sb.append(" DEFAULT '").append(defaultVal).append("'");
            }
            try (Statement s = conn.createStatement()) { s.execute(sb.toString()); }
            ctx.json(Map.of("ok", true));
        } catch (SQLSyntaxErrorException e) {
            logger.warn("addColumn syntax error: {}", e.getMessage());
            ctx.status(400).json(Map.of("error", "カラム追加に失敗しました"));
        } catch (Exception e) {
            logger.error("addColumn error", e);
            ctx.status(503).json(Map.of("error", "Service temporarily unavailable"));
        }
    }

    // 管理者パネルのdbページでSQLクエリを実行するエンドポイント　一番重要
    @PostMapping("/admin/sql")
    public void execSql(Context ctx) {
        String executor = ctx.getAttribute(CfAccessAuth.ATTR_VERIFIED_EMAIL);
        try (Connection conn = Database.getConnection()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAs(Map.class);
            if (body == null) { ctx.status(400).json(Map.of("error", "リクエストボディが必要です")); return; }
            String sql = (String) body.get("sql");
            if (sql == null || sql.isBlank()) { ctx.status(400).json(Map.of("error", "sqlが必要です")); return; }

            ObjectNode lastResult = null;
            for (String raw : splitStatements(sql)) {
                String stmt = raw.strip();
                if (stmt.isEmpty()) continue;
                String norm = WHITESPACE_PATTERN.matcher(stripSqlComments(stmt).toUpperCase()).replaceAll(" ").trim();
                String[] words = WHITESPACE_PATTERN.split(norm, 3);
                String first = words.length > 0 ? words[0] : "";
                if (!ALLOWED_SQL_KEYWORDS.contains(first)) {
                    ctx.status(403).json(Map.of("error", "この操作は許可されています: " + first));
                    return;
                }
                if ("ALTER".equals(first)) {
                    String second = words.length > 1 ? words[1] : "";
                    if (!"TABLE".equals(second)) {
                        ctx.status(403).json(Map.of("error", "この操作は許可されています: ALTER " + second));
                        return;
                    }
                }
                logger.info("execSql by={} len={}", executor, stmt.length());
                try (Statement s = conn.createStatement()) {
                    s.setQueryTimeout(30);
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
                                    putValue(row, meta.getColumnName(i), getColumnValue(rs, meta, i));
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
            logger.error("execSql error by={}", executor, e);
            ctx.status(400).json(Map.of("error", sanitizeSqlError(e)));
        }
    }

    private static String stripSqlComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int n = s.length();
        for (int i = 0; i < n; ) {
            char c = s.charAt(i);
            if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
                i += 2;
                while (i < n && s.charAt(i) != '\n') i++;
                out.append(' ');
                continue;
            }
            if (c == '#') {
                i++;
                while (i < n && s.charAt(i) != '\n') i++;
                out.append(' ');
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                boolean exec = (i + 2 < n && s.charAt(i + 2) == '!');
                i += 2;
                if (exec) {
                    i++;
                    while (i < n && Character.isDigit(s.charAt(i))) i++;
                }
                int start = i;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                if (i + 1 < n) {
                    if (exec) out.append(' ').append(s, start, i).append(' ');
                    else      out.append(' ');
                    i += 2;
                } else {
                    if (exec) out.append(' ').append(s, start, n).append(' ');
                    else      out.append(' ');
                    i = n;
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static List<String> splitStatements(String sql) {
        List<String> stmts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                char q = c;
                cur.append(c);
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    cur.append(d);
                    i++;
                    if (d == '\\') {
                        if (i < n) { cur.append(sql.charAt(i)); i++; }
                    } else if (d == q) {
                        if (i < n && sql.charAt(i) == q) { cur.append(q); i++; }
                        else break;
                    }
                }
            } else if (c == ';') {
                String stmt = cur.toString().strip();
                if (!stmt.isEmpty()) stmts.add(stmt);
                cur = new StringBuilder();
                i++;
            } else {
                cur.append(c);
                i++;
            }
        }
        String last = cur.toString().strip();
        if (!last.isEmpty()) stmts.add(last);
        return stmts;
    }

    private String sanitizeSqlError(Exception e) {
        if (e instanceof SQLSyntaxErrorException)              return "SQL 構文エラー";
        if (e instanceof SQLIntegrityConstraintViolationException) return "制約違反";
        if (e instanceof SQLException se && isDataTypeError(se)) return "データ型エラー";
        return "クエリ実行に失敗しました";
    }

    // 管理者パネルのstatsページでリクエスト統計を取得するエンドポイント
    @GetMapping("/admin/stats")
    public void stats(Context ctx) {
        ctx.header("Cache-Control", "no-store");
        RequestMetrics m = RequestMetrics.get();
        long   total    = m.getTotalRequests();
        long   errors   = m.getErrorCount();
        double errRate  = total == 0 ? 0.0 : Math.round((errors * 100.0 / total) * 100.0) / 100.0;
        long[] perc     = m.getPercentiles();

        ObjectNode root = mapper.createObjectNode();
        root.put("total_requests", total);
        root.put("error_count",    errors);
        root.put("error_rate",     errRate);
        root.put("p50_ms",         perc[0]);
        root.put("p95_ms",         perc[1]);
        root.put("instances",      fetchCurrentInstanceCount());
        root.put("max_instances",  10);

        ArrayNode chart = root.putArray("chart");
        for (long v : m.getHourlyCounts()) chart.add(v);

        ArrayNode endpoints = root.putArray("endpoints");
        for (var e : m.getTopEndpoints(TOP_ENDPOINTS_COUNT)) {
            String[] parts = e.getKey().split(" ", 2);
            String path = parts.length > 1 ? parts[1] : "";
            if (path.startsWith("/admin")) continue;
            addEndpoint(endpoints, parts[0], path, e.getValue());
        }

        ArrayNode system = root.putArray("system");
        addStatus(system, "Database",   "ok", "Connected");
        addStatus(system, "API Server", "ok", "Running");
        ctx.json(root);
    }

    @GetMapping("/admin/stats/daily")
    public void dailyStats(Context ctx) {
        ctx.header("Cache-Control", "no-store");
        ZonedDateTime jstNow = ZonedDateTime.now(ZoneId.of("Asia/Tokyo"));
        String today     = jstNow.toLocalDate().toString();
        String yesterday = jstNow.toLocalDate().minusDays(1).toString();

        RequestMetrics m = RequestMetrics.get();
        List<Map.Entry<String, Long>> todayEps     = m.getEndpointsByDate(today);
        List<Map.Entry<String, Long>> yesterdayEps = m.getEndpointsByDate(yesterday);

        ObjectNode root = mapper.createObjectNode();
        root.put("today",     today);
        root.put("yesterday", yesterday);

        long todayTotal     = todayEps.stream().mapToLong(Map.Entry::getValue).sum();
        long yesterdayTotal = yesterdayEps.stream().mapToLong(Map.Entry::getValue).sum();
        root.put("today_total",     todayTotal);
        root.put("yesterday_total", yesterdayTotal);
        root.put("diff",            todayTotal - yesterdayTotal);

        Map<String, long[]> merged = new LinkedHashMap<>();
        for (var e : todayEps)     merged.computeIfAbsent(e.getKey(), k -> new long[2])[0] = e.getValue();
        for (var e : yesterdayEps) merged.computeIfAbsent(e.getKey(), k -> new long[2])[1] = e.getValue();

        ArrayNode eps = root.putArray("endpoints");
        for (var entry : merged.entrySet()) {
            String[] parts = entry.getKey().split(" ", 2);
            ObjectNode n = eps.addObject();
            n.put("method",    parts.length > 0 ? parts[0] : "");
            n.put("path",      parts.length > 1 ? parts[1] : "");
            n.put("today",     entry.getValue()[0]);
            n.put("yesterday", entry.getValue()[1]);
            n.put("diff",      entry.getValue()[0] - entry.getValue()[1]);
        }
        ctx.json(root);
    }

    private int fetchCurrentInstanceCount() {
        try {
            HttpRequest tokenReq = HttpRequest.newBuilder()
                .uri(URI.create(GCP_METADATA_BASE + "instance/service-accounts/default/token"))
                .header("Metadata-Flavor", "Google")
                .timeout(Duration.ofSeconds(3))
                .GET().build();
            String accessToken = mapper.readTree(
                http.send(tokenReq, HttpResponse.BodyHandlers.ofString()).body()
            ).get("access_token").asText();

            HttpRequest projReq = HttpRequest.newBuilder()
                .uri(URI.create(GCP_METADATA_BASE + "project/project-id"))
                .header("Metadata-Flavor", "Google")
                .timeout(Duration.ofSeconds(3))
                .GET().build();
            String projectId = http.send(projReq, HttpResponse.BodyHandlers.ofString()).body().strip();

            String service = System.getenv().getOrDefault("K_SERVICE", "");
            String serviceClause = service.isBlank() ? ""
                : " AND resource.labels.service_name=\"" + service + "\"";
            String filter = "metric.type=\"run.googleapis.com/container/instance_count\"" + serviceClause;

            Instant now   = Instant.now();
            Instant start = now.minusSeconds(300);
            String url = GCP_MONITORING_BASE + projectId + "/timeSeries"
                + "?filter="                         + URLEncoder.encode(filter, StandardCharsets.UTF_8)
                + "&interval.startTime="             + start
                + "&interval.endTime="               + now
                + "&aggregation.alignmentPeriod=60s"
                + "&aggregation.perSeriesAligner=ALIGN_MAX"
                + "&aggregation.crossSeriesReducer=REDUCE_MAX"
                + "&aggregation.groupByFields=resource.labels.service_name";

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(5))
                .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return 0;

            JsonNode timeSeries = mapper.readTree(res.body()).path("timeSeries");
            if (!timeSeries.isArray() || timeSeries.isEmpty()) return 0;

            long max = 0;
            for (JsonNode series : timeSeries) {
                for (JsonNode point : series.path("points")) {
                    JsonNode val = point.path("value");
                    long v = val.has("int64Value")  ? val.get("int64Value").asLong()
                           : val.has("doubleValue") ? (long) val.get("doubleValue").asDouble()
                           : 0L;
                    if (v > max) max = v;
                }
            }
            return (int) max;
        } catch (Exception e) {
            logger.debug("fetchCurrentInstanceCount unavailable: {}", e.getMessage());
            return 0;
        }
    }

    private Object getColumnValue(ResultSet rs, ResultSetMetaData meta, int i) throws SQLException {
        int type = meta.getColumnType(i);
        if (type == Types.TINYINT || type == Types.BIT) {
            int v = rs.getInt(i);
            return rs.wasNull() ? null : v;
        }
        return rs.getObject(i);
    }

    private void putValue(ObjectNode row, String col, Object val) {
        if (val == null)              { row.putNull(col); return; }
        if (val instanceof Long    v) { row.put(col, v); return; }
        if (val instanceof Integer v) { row.put(col, v); return; }
        if (val instanceof Double  v) { row.put(col, v); return; }
        if (val instanceof Float   v) { row.put(col, v); return; }
        if (val instanceof Boolean v) { row.put(col, v ? 1 : 0); return; }
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
            ctx.status(400).json(Map.of("error", "テーブル名が無効です"));
            return false;
        }
        return true;
    }

    private boolean isValidIdentifier(String s) {
        return s != null && IDENTIFIER_PATTERN.matcher(s).matches();
    }

    private Object normalizeValue(Object val) {
        if (val instanceof Boolean b) return b ? 1 : 0;
        return val;
    }

    private String normalizeColumnType(String typeName, int size) {
        if (typeName == null) return "unknown";
        String t = typeName.toUpperCase();
        return switch (t) {
            case "VARCHAR", "NVARCHAR"           -> "VARCHAR(" + size + ")";
            case "CHAR", "NCHAR"                 -> "CHAR(" + size + ")";
            case "INT", "INTEGER"                -> "INT";
            case "TINYINT"                       -> "TINYINT(" + size + ")";
            case "BIGINT"                        -> "BIGINT";
            case "FLOAT"                         -> "FLOAT";
            case "DOUBLE", "DOUBLE PRECISION"    -> "DOUBLE";
            case "DECIMAL", "NUMERIC"            -> "DECIMAL";
            case "TEXT", "LONGTEXT",
                 "MEDIUMTEXT", "TINYTEXT"        -> t;
            case "DATE"                          -> "DATE";
            case "DATETIME", "TIMESTAMP"         -> "DATETIME";
            case "TIME"                          -> "TIME";
            default                              -> typeName.toLowerCase();
        };
    }

    private String toUserMessage(SQLIntegrityConstraintViolationException e) {
        int code = e.getErrorCode();
        String msg = e.getMessage();
        if (code == 1062) return "Duplicate entry: " + extractDuplicateValue(msg);
        if (code == 1048) return "Column cannot be null: " + extractColumnName(msg);
        if (code == 1216 || code == 1217 || code == 1451 || code == 1452)
            return "Foreign key constraint violation";
        return "Constraint violation";
    }

    private boolean isDataTypeError(SQLException e) {
        int code = e.getErrorCode();
        return code == 1292 || code == 1366;
    }

    private String toDataTypeMessage() {
        return "Incorrect value for column type";
    }

    private String extractDuplicateValue(String msg) {
        int s = msg.indexOf("'"), e = msg.indexOf("'", s + 1);
        return (s >= 0 && e > s) ? msg.substring(s + 1, e) : msg;
    }

    private String extractColumnName(String msg) {
        int s = msg.indexOf("'"), e = msg.indexOf("'", s + 1);
        return (s >= 0 && e > s) ? msg.substring(s + 1, e) : msg;
    }

    private String getPkColumn(Connection conn, String table) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getPrimaryKeys(null, null, table)) {
            if (rs.next()) return rs.getString("COLUMN_NAME");
        }
        return null;
    }

    private String resolveTableName(Connection conn, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("TABLE_NAME") : null;
            }
        }
    }

    private List<String> getColumnNames(Connection conn, String table) throws SQLException {
        List<String> cols = new ArrayList<>();
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, table, null)) {
            while (rs.next()) cols.add(rs.getString("COLUMN_NAME"));
        }
        return cols;
    }
}
