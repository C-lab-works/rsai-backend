package dev.gate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

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
    private static final ObjectMapper mapper              = dev.gate.core.Json.MAPPER;
    private static final HttpClient   http                = dev.gate.core.Http.CLIENT;
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[a-zA-Z0-9_]+");
    private static final Pattern DEFAULT_VALUE_PATTERN = Pattern.compile("[a-zA-Z0-9._\\-]+");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern YAML_IDENT = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    // SQLコマンドホワイトリスト
    private static final Set<String> ALLOWED_SQL_KEYWORDS = Set.of(
        "SELECT", "INSERT", "UPDATE", "DELETE",
        "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "ANALYZE",
        "ALTER"
    );

    // ファイルシステムアクセス系キーワード（正規化済み文字列への部分一致で拒否）
    private static final List<String> BLOCKED_SQL_FRAGMENTS = List.of(
        "INTO OUTFILE", "INTO DUMPFILE", "LOAD_FILE", "LOAD DATA"
    );

    // 書き込み系（INSERT/UPDATE/DELETE）と DDL（ALTER）。Discord 通知の要否判定に使用。
    private static final Set<String> WRITE_SQL_KEYWORDS = Set.of(
        "INSERT", "UPDATE", "DELETE", "ALTER"
    );

    // インスタンスコマンドホワイトリスト
    private static final Set<String> ALLOWED_INSTANCE_COMMANDS = Set.of(
        "ping", "cpu", "heap", "thread-count", "gc",
        "cache-stats", "logs", "log-level", "stop"
    );

    // instanceId 検証パターン（Cloud Run の HOSTNAME は英数字とハイフンのみ）
    private static final Pattern INSTANCE_ID_PATTERN = Pattern.compile("[a-zA-Z0-9_-]{1,256}");
    private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static final int TOP_ENDPOINTS_COUNT = 10;
    private static final int MAX_RESULT_ROWS = 1000;

    private static final Set<String> ALLOWED_COL_TYPES = Set.of(
        "INT", "BIGINT", "VARCHAR(255)", "VARCHAR(100)", "TEXT",
        "TINYINT(1)", "FLOAT", "DOUBLE", "DATE", "DATETIME", "TIME"
    );

    /** インスタンスのステータス付きビュー（listInstances / listInstancesMetrics 共通）。 */
    private record InstanceView(String id, String revision, String host, String startedAt, String status) {}

    /** Firestore 不可時のフォールバック: 自インスタンスのみを返す。 */
    private static InstanceView selfInstanceView() {
        InstanceManager im = InstanceManager.get();
        String buildSha = System.getenv("BUILD_SHA");
        String revision = im.getInstanceId()
            + (buildSha != null && !buildSha.isBlank()
                ? "-" + buildSha.substring(0, Math.min(8, buildSha.length()))
                : "");
        return new InstanceView(im.getInstanceId(), revision, System.getenv("HOSTNAME"),
            im.getStartedAt().toString(), "running");
    }

    /**
     * Firestore の instances コレクションからステータスを派生させたビュー一覧を返す。
     * lastSeen が 30 秒以上前のものは一覧から除外し、heartbeat 未到達のまま
     * 300 秒経過したドキュメントは削除する。
     */
    private List<InstanceView> deriveInstanceViews(Instant now) throws Exception {
        List<InstanceView> views = new ArrayList<>();
        for (FirestoreRest.Entry entry : FirestoreRest.get().list("instances")) {
            Map<String, Object> d   = entry.data();
            String rawStatus        = (String) d.get("status");
            String lastSeenStr      = (String) d.get("lastSeen");

            String status;
            if ("stopped".equals(rawStatus)) {
                status = "stopped";
            } else if (lastSeenStr != null) {
                long age = Duration.between(Instant.parse(lastSeenStr), now).toSeconds();
                if (age >= 30) continue;
                status = age < 10 ? "running" : "degraded";
            } else {
                String startedAt = (String) d.get("startedAt");
                if (startedAt != null) {
                    long sinceStart = Duration.between(Instant.parse(startedAt), now).toSeconds();
                    if (sinceStart >= 300) {
                        try { FirestoreRest.get().delete("instances/" + entry.id()); } catch (Exception ignored) {}
                        continue;
                    }
                }
                status = "stopped";
            }
            views.add(new InstanceView(
                entry.id(),
                (String) d.get("revision"),
                (String) d.get("host"),
                (String) d.get("startedAt"),
                status
            ));
        }
        return views;
    }

    /** InstanceView を arr に JSON ノードとして追加し、そのノードを返す。 */
    private ObjectNode addInstanceViewNode(ArrayNode arr, InstanceView v) {
        ObjectNode n = arr.addObject();
        n.put("instanceId", v.id());
        putStringField(n, "revision",  v.revision());
        putStringField(n, "host",      v.host());
        putStringField(n, "startedAt", v.startedAt());
        n.put("status", v.status());
        return n;
    }

    // インスタンス一覧を返す（lastSeen からステータスを派生）
    @GetMapping("/admin/instances")
    public void listInstances(Context ctx) {
        ctx.header("Cache-Control", "no-store");
        if (!FirestoreRest.get().isAvailable()) {
            ArrayNode arr = mapper.createArrayNode();
            addInstanceViewNode(arr, selfInstanceView());
            ctx.json(arr);
            return;
        }
        try {
            ArrayNode arr = mapper.createArrayNode();
            for (InstanceView v : deriveInstanceViews(Instant.now())) {
                addInstanceViewNode(arr, v);
            }
            ctx.json(arr);
        } catch (Exception e) {
            logger.error("listInstances error", e);
            ctx.status(503).json(Map.of("error", "Firestore unavailable"));
        }
    }

    // 全インスタンスのメトリクス履歴を一括取得する（非 stopped インスタンスを並列フェッチ）
    // GET /admin/instances/metrics?limit=20
    // Router の find() は exactRoutes を先に検索するため、
    // parameterized route /admin/instances/{id} より先にこちらがマッチする。
    @GetMapping("/admin/instances/metrics")
    public void listInstancesMetrics(Context ctx) {
        ctx.header("Cache-Control", "no-store");
        int limit = 20;
        try { limit = Math.max(1, Math.min(200, Integer.parseInt(ctx.query("limit")))); } catch (Exception ignored) {}
        final int effectiveLimit = limit;

        if (!FirestoreRest.get().isAvailable()) {
            // listInstances と同じフォールバック: 自インスタンスのみ（メトリクスは Firestore 依存のため空）
            ArrayNode arr = mapper.createArrayNode();
            addInstanceViewNode(arr, selfInstanceView()).set("points", mapper.createArrayNode());
            ctx.json(arr);
            return;
        }
        try {
            List<InstanceView> instances = deriveInstanceViews(Instant.now());

            // 非 stopped インスタンスのメトリクスを並列フェッチする（clearCache / Main.java と同パターン）
            List<Future<ArrayNode>> futures = new ArrayList<>();
            for (InstanceView info : instances) {
                if ("stopped".equals(info.status())) {
                    futures.add(null); // stopped はフェッチしない
                } else {
                    final String instanceId = info.id();
                    futures.add(Main.bg.submit(() -> {
                        ArrayNode pts = mapper.createArrayNode();
                        for (FirestoreRest.Entry e :
                                FirestoreRest.get().query("instances/" + instanceId, "metrics", "t", true, effectiveLimit)) {
                            Map<String, Object> d = e.data();
                            ObjectNode n = pts.addObject();
                            n.put("t",            toLong(d.get("t")));
                            n.put("cpu",          toDouble(d.get("cpu")));
                            n.put("heap_used_mb", toLong(d.get("heap_used_mb")));
                            n.put("threads",      (int) toLong(d.get("threads")));
                        }
                        return pts;
                    }));
                }
            }

            // 結果を収集。単一インスタンスのフェッチ失敗は警告ログのみ、全体は 200 を維持する
            ArrayNode arr = mapper.createArrayNode();
            for (int i = 0; i < instances.size(); i++) {
                InstanceView info = instances.get(i);
                ObjectNode n = addInstanceViewNode(arr, info);

                ArrayNode points;
                if (futures.get(i) == null) {
                    points = mapper.createArrayNode(); // stopped はフェッチしない
                } else {
                    try {
                        points = futures.get(i).get();
                    } catch (Exception e) {
                        logger.warn("listInstancesMetrics: failed to fetch metrics for instanceId={}: {}",
                            info.id(), e.getMessage());
                        points = mapper.createArrayNode();
                    }
                }
                n.set("points", points);
            }
            ctx.json(arr);
        } catch (Exception e) {
            logger.error("listInstancesMetrics error", e);
            ctx.status(503).json(Map.of("error", "Firestore unavailable"));
        }
    }

    private static void putStringField(ObjectNode n, String key, String value) {
        if (value != null) n.put(key, value); else n.putNull(key);
    }

    // instanceId がパストラバーサル不可能な形式か検証。不正なら 400 を返して true を返す。
    private static boolean rejectInvalidInstanceId(Context ctx, String instanceId) {
        if (instanceId == null || !INSTANCE_ID_PATTERN.matcher(instanceId).matches()) {
            ctx.status(400).json(Map.of("error", "Invalid instance ID"));
            return true;
        }
        return false;
    }

    // インスタンスにコマンドを発行し、即座に requestId を返す（非同期）
    // 結果は GET /admin/instances/{id}/command/{requestId} でポーリングする
    @PostMapping("/admin/instances/{id}/command")
    @SuppressWarnings("unchecked")
    public void sendCommand(Context ctx) {
        String instanceId = ctx.pathParam("id");
        if (rejectInvalidInstanceId(ctx, instanceId)) return;
        try {
            Map<String, Object> body = ctx.bodyAs(Map.class);
            if (body == null || !body.containsKey("type")) {
                ctx.status(400).json(Map.of("error", "type is required"));
                return;
            }
            String type = (String) body.get("type");
            if (!ALLOWED_INSTANCE_COMMANDS.contains(type)) {
                ctx.status(400).json(Map.of("error", "Unknown command type"));
                return;
            }
            Object payloadRaw = body.get("payload");

            String requestId = UUID.randomUUID().toString();

            Map<String, Object> cmd = new java.util.HashMap<>();
            cmd.put("type",      type);
            cmd.put("requestId", requestId);
            cmd.put("issuedAt",  Instant.now().toString());
            if (payloadRaw != null) cmd.put("payload", payloadRaw);

            // Firestore 書き込みを非同期実行してハンドラを即座に返す。
            // 同期実行では Jetty の IdleTimeout (30s) が発動して 504 になる。
            final Map<String, Object> cmdAsync = Map.copyOf(cmd);
            CompletableFuture.runAsync(() -> {
                try {
                    FirestoreRest.get().update("instances/" + instanceId, Map.of("cmd", cmdAsync));
                } catch (Exception e) {
                    logger.error("sendCommand async write failed instanceId={}", instanceId, e);
                }
            });

            ctx.status(202).json(Map.of("requestId", requestId));
        } catch (dev.gate.core.ClientErrorException ce) {
            throw ce;
        } catch (Exception e) {
            logger.error("sendCommand error instanceId={}", instanceId, e);
            ctx.status(503).json(Map.of("error", "Firestore unavailable"));
        }
    }

    // インスタンスコマンドの実行結果を取得する（ポーリング用）
    @GetMapping("/admin/instances/{id}/command/{requestId}")
    @SuppressWarnings("unchecked")
    public void getCommandResult(Context ctx) {
        String instanceId = ctx.pathParam("id");
        if (rejectInvalidInstanceId(ctx, instanceId)) return;
        String requestId = ctx.pathParam("requestId");
        if (requestId == null || !UUID_PATTERN.matcher(requestId).matches()) {
            ctx.status(400).json(Map.of("error", "Invalid requestId"));
            return;
        }
        ctx.header("Cache-Control", "no-store");
        try {
            Map<String, Object> doc = FirestoreRest.get().get("instances/" + instanceId);
            if (doc == null) {
                ctx.status(404).json(Map.of("error", "instance not found"));
                return;
            }
            Map<String, Object> res = (Map<String, Object>) doc.get("res");
            if (res != null && requestId.equals(res.get("requestId"))) {
                ctx.json(res);
                return;
            }
            ctx.status(202).json(Map.of("status", "pending", "requestId", requestId));
        } catch (Exception e) {
            logger.error("getCommandResult error instanceId={}", instanceId, e);
            ctx.status(503).json(Map.of("error", "Firestore unavailable"));
        }
    }

    // インスタンスのメトリクス履歴を返す（降順 → フロントで昇順に並べ直す）
    @GetMapping("/admin/instances/{id}/metrics")
    public void getInstanceMetrics(Context ctx) {
        ctx.header("Cache-Control", "no-store");
        String instanceId = ctx.pathParam("id");
        if (rejectInvalidInstanceId(ctx, instanceId)) return;
        int limit = 40;
        try { limit = Math.max(1, Math.min(200, Integer.parseInt(ctx.query("limit")))); } catch (Exception ignored) {}
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

    // stopped インスタンスの Firestore ドキュメントを削除する
    @DeleteMapping("/admin/instances/{id}")
    public void deleteInstance(Context ctx) {
        String instanceId = ctx.pathParam("id");
        if (rejectInvalidInstanceId(ctx, instanceId)) return;
        try {
            Map<String, Object> doc = FirestoreRest.get().get("instances/" + instanceId);
            if (doc == null) {
                ctx.status(404).json(Map.of("error", "インスタンスが見つかりません"));
                return;
            }
            if (!"stopped".equals(doc.get("status"))) {
                ctx.status(409).json(Map.of("error", "実行中のインスタンスは削除できません"));
                return;
            }
            FirestoreRest.get().delete("instances/" + instanceId);
            ctx.json(Map.of("ok", true));
        } catch (Exception e) {
            logger.error("deleteInstance error instanceId={}", instanceId, e);
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
            // 4 つのキャッシュ再構築を並列実行する（#13）
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            futures.add(Main.bg.submit((java.util.concurrent.Callable<Void>) () -> { new DataController().refreshAll(); return null; }));
            futures.add(Main.bg.submit((java.util.concurrent.Callable<Void>) () -> { AnnouncementsController.refreshCache(); return null; }));
            futures.add(Main.bg.submit((java.util.concurrent.Callable<Void>) () -> { CongestionController.refreshCache(); return null; }));
            futures.add(Main.bg.submit((java.util.concurrent.Callable<Void>) () -> { dev.gate.core.YamlRouteLoader.refreshAll(); return null; }));
            Exception firstError = null;
            for (java.util.concurrent.Future<?> f : futures) {
                try { f.get(); }
                catch (java.util.concurrent.ExecutionException ex) { if (firstError == null) firstError = (Exception) ex.getCause(); }
            }
            if (firstError != null) throw firstError;

            String clearedBy = ctx.getAttribute(CfAccessAuth.ATTR_VERIFIED_EMAIL);
            logger.info("cache refreshed by=" + clearedBy);

            InstanceManager.get().broadcastCacheRefresh();

            boolean cfPurged = CfPurge.purgeEverythingSync();

            ObjectNode res = mapper.createObjectNode();
            res.put("ok", true);
            res.put("cf_cache_purged", cfPurged);
            ArrayNode cleared = res.putArray("refreshed");
            for (String key : new String[]{"events", "food", "map", "announcements", "congestion", "api"}) {
                cleared.add(key);
            }
            ctx.json(res);
        } catch (Exception e) {
            logger.error("clearCache error", e);
            ctx.status(500).json(Map.of("error", "Cache refresh failed"));
        }
    }

    // 管理者パネルから意図的に503エラーを発生させるエンドポイント
    @GetMapping("/admin/debug/503")
    public void debug503(Context ctx) {
        String instanceId = Optional.ofNullable(System.getenv("HOSTNAME")).orElse("local");
        ctx.status(503).json(Map.of("error", "Debug: intentional 503 (instance: " + instanceId + ")"));
    }

    // 管理者パネルのdbページでテーブル一覧を取得するエンドポイント
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

            // getPrimaryKeys を 1 回だけ呼び、PK セットと KEY_SEQ 順の最初の列を両方取得する（#14a）
            Set<String> pks = new HashSet<>();
            // KEY_SEQ 昇順で並ぶため、最初に追加されたものが KEY_SEQ 最小（= 主キー代表列）
            String firstPkCol = null;
            try (ResultSet rs = meta.getPrimaryKeys(null, null, resolvedTable)) {
                // KEY_SEQ でソートされた順に読む
                java.util.TreeMap<Short, String> pkBySeq = new java.util.TreeMap<>();
                while (rs.next()) {
                    pks.add(rs.getString("COLUMN_NAME"));
                    pkBySeq.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
                }
                if (!pkBySeq.isEmpty()) firstPkCol = pkBySeq.firstEntry().getValue();
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
            // firstPkCol は上で取得済み。getPkColumn() の二重呼び出しを廃止（#14a）
            String pkCol = firstPkCol;
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
        String user = ctx.getAttribute(CfAccessAuth.ATTR_VERIFIED_EMAIL);
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
                int updated = ps.executeUpdate();
                AuditLog.write(user, "UPDATE_ROW", table + "/" + pkVal, null, "ok", null);
                CacheSync.scheduleFullSync("updateRow:" + table);
                ctx.json(Map.of("updated", updated));
            }
        } catch (dev.gate.core.ClientErrorException ce) {
            throw ce;
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
        String user = ctx.getAttribute(CfAccessAuth.ATTR_VERIFIED_EMAIL);
        if (!isValidTableName(table, ctx)) return;
        try (Connection conn = Database.getConnection()) {
            String resolvedTable = resolveTableName(conn, table);
            if (resolvedTable == null) { ctx.status(404).json(Map.of("error", "テーブルが見つかりません")); return; }

            String pkCol = getPkColumn(conn, resolvedTable);
            if (pkCol == null) { ctx.status(400).json(Map.of("error", "主キーが見つかりません")); return; }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM `" + resolvedTable + "` WHERE `" + pkCol + "` = ?")) {
                ps.setString(1, pkVal);
                int deleted = ps.executeUpdate();
                AuditLog.write(user, "DELETE_ROW", table + "/" + pkVal, null, "ok", null);
                CacheSync.scheduleFullSync("deleteRow:" + table);
                ctx.json(Map.of("deleted", deleted));
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
        String user = ctx.getAttribute(CfAccessAuth.ATTR_VERIFIED_EMAIL);
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
                AuditLog.write(user, "INSERT_ROW", table, null, "ok", null);
                CacheSync.scheduleFullSync("insertRow:" + table);
                try (ResultSet gen = ps.getGeneratedKeys()) {
                    if (gen.next()) ctx.json(Map.of("id", gen.getLong(1)));
                    else ctx.json(Map.of("ok", true));
                }
            }
        } catch (dev.gate.core.ClientErrorException ce) {
            throw ce;
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
        String user = ctx.getAttribute(CfAccessAuth.ATTR_VERIFIED_EMAIL);
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

            StringBuilder sb = new StringBuilder("CREATE TABLE `").append(escapeSqlIdentifier(tableName)).append("` (");
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
                StringBuilder colDef = new StringBuilder("`").append(escapeSqlIdentifier(name)).append("` ").append(type);
                if (Boolean.TRUE.equals(col.get("notNull")))       colDef.append(" NOT NULL");
                if (Boolean.TRUE.equals(col.get("autoIncrement"))) colDef.append(" AUTO_INCREMENT");
                if (Boolean.TRUE.equals(col.get("pk")))            colDef.append(" PRIMARY KEY");
                colDefs.add(colDef.toString());
            }
            sb.append(String.join(", ", colDefs)).append(")");

            try (Statement s = conn.createStatement()) { s.execute(sb.toString()); }
            AuditLog.write(user, "CREATE_TABLE", tableName, columns.size() + " columns", "ok", null);
            DiscordWebhook.sendAdminOp(user, "CREATE_TABLE", tableName, columns.size() + " columns");
            ctx.json(Map.of("ok", true));
        } catch (dev.gate.core.ClientErrorException ce) {
            throw ce;
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
        String user = ctx.getAttribute(CfAccessAuth.ATTR_VERIFIED_EMAIL);
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
                .append(escapeSqlIdentifier(resolvedTable)).append("` ADD COLUMN `")
                .append(escapeSqlIdentifier(colName)).append("` ").append(colType);
            if (notNull) sb.append(" NOT NULL");
            if (defaultVal != null && !defaultVal.isEmpty()) {
                if (!DEFAULT_VALUE_PATTERN.matcher(defaultVal).matches()) {
                    ctx.status(400).json(Map.of("error", "デフォルト値に使えない文字が含まれています")); return;
                }
                sb.append(" DEFAULT '").append(defaultVal.replace("'", "''")).append("'");
            }
            try (Statement s = conn.createStatement()) { s.execute(sb.toString()); }
            AuditLog.write(user, "ADD_COLUMN", resolvedTable + "/" + colName, colType, "ok", null);
            DiscordWebhook.sendAdminOp(user, "ADD_COLUMN", resolvedTable + "/" + colName, colType);
            ctx.json(Map.of("ok", true));
        } catch (dev.gate.core.ClientErrorException ce) {
            throw ce;
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

            // 事前検証: ホワイトリストチェックと DDL 検出を一括で行う
            List<String> stmts = new ArrayList<>();
            boolean hasDdl = false;
            boolean hasWrite = false;
            for (String raw : splitStatements(sql)) {
                String stmt = raw.strip();
                if (stmt.isEmpty()) continue;
                String norm = normalizeSql(stmt);
                // ファイルアクセス系（INTO OUTFILE/DUMPFILE, LOAD_FILE, LOAD DATA）を拒否。
                // norm は大文字化＋空白圧縮済みなので BLOCKED_SQL_FRAGMENTS とそのまま部分一致できる。
                // 注: stripSqlComments は文字列リテラルを除去しないため、リテラル内に該当語があると
                // 誤検知し得るが、admin 専用ツールゆえ実害なしとして許容する（多層防御を優先）。
                String blocked = matchedBlockedFragment(norm);
                if (blocked != null) {
                    ctx.status(403).json(Map.of("error", "ファイルアクセス系操作は許可されていません: " + blocked));
                    return;
                }
                String[] words = WHITESPACE_PATTERN.split(norm, 3);
                String first = words.length > 0 ? words[0] : "";
                if (!ALLOWED_SQL_KEYWORDS.contains(first)) {
                    ctx.status(403).json(Map.of("error", "この操作は許可されていません: " + first));
                    return;
                }
                if (isWriteKeyword(first)) hasWrite = true;
                if ("ALTER".equals(first)) {
                    String second = words.length > 1 ? words[1] : "";
                    if (!"TABLE".equals(second)) {
                        ctx.status(403).json(Map.of("error", "この操作は許可されていません: ALTER " + second));
                        return;
                    }
                    hasDdl = true;
                }
                stmts.add(stmt);
            }

            // DDL (ALTER TABLE) がない場合のみトランザクションで包む。
            // MySQL の DDL は暗黙コミットされるためロールバック不可。
            if (!hasDdl) conn.setAutoCommit(false);
            try {
                ObjectNode lastResult = null;
                for (String stmt : stmts) {
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
                                int rowCount = 0;
                                boolean truncated = false;
                                while (rs.next()) {
                                    if (rowCount >= MAX_RESULT_ROWS) {
                                        truncated = true;
                                        break;
                                    }
                                    ObjectNode row = rowsNode.addObject();
                                    for (int i = 1; i <= colCount; i++) {
                                        putValue(row, meta.getColumnName(i), getColumnValue(rs, meta, i));
                                    }
                                    rowCount++;
                                }
                                if (truncated) lastResult.put("truncated", true);
                            }
                        } else {
                            lastResult.put("affected", s.getUpdateCount());
                        }
                    }
                }
                if (!hasDdl) conn.commit();
                String sqlTarget = sql.trim().substring(0, Math.min(50, sql.trim().length()));
                String sqlDetail = sql.length() > 200 ? sql.substring(0, 200) + "..." : sql;
                AuditLog.write(executor, "EXECUTE_SQL", sqlTarget, sqlDetail, "ok", null);
                if (hasWrite) DiscordWebhook.sendAdminOp(executor, "EXECUTE_SQL", sqlTarget, sqlDetail);
                if (hasWrite || hasDdl) CacheSync.scheduleFullSync("execSql");
                ctx.json(lastResult != null ? lastResult : mapper.createObjectNode());
            } catch (Exception e) {
                if (!hasDdl) {
                    try { conn.rollback(); } catch (Exception re) { logger.warn("execSql rollback failed", re); }
                }
                throw e;
            } finally {
                if (!hasDdl) conn.setAutoCommit(true);
            }
        } catch (dev.gate.core.ClientErrorException ce) {
            throw ce;
        } catch (Exception e) {
            logger.error("execSql error by={}", executor, e);
            ctx.status(400).json(Map.of("error", sanitizeSqlError(e)));
        }
    }

    // SQL 文を検証用に正規化する: コメント除去 → 大文字化 → 連続空白を単一空白へ圧縮 → trim。
    // テスト容易化と execSql とのロジック共有のため package-private。
    static String normalizeSql(String stmt) {
        return WHITESPACE_PATTERN.matcher(stripSqlComments(stmt).toUpperCase()).replaceAll(" ").trim();
    }

    // 先頭キーワードが書き込み系（INSERT/UPDATE/DELETE/ALTER）か。Discord 通知要否の判定に使用。
    static boolean isWriteKeyword(String firstKeyword) {
        return WRITE_SQL_KEYWORDS.contains(firstKeyword);
    }

    // 正規化済み（大文字化＋空白圧縮）SQL に含まれる最初のブロック対象フラグメントを返す。
    // 該当なしは null。テスト容易化のため package-private。
    static String matchedBlockedFragment(String norm) {
        for (String frag : BLOCKED_SQL_FRAGMENTS) {
            if (norm.contains(frag)) return frag;
        }
        return null;
    }

    static String stripSqlComments(String s) {
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

    static List<String> splitStatements(String sql) {
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
        m.refreshSnapshot();
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
        root.put("instances",      countRunningInstances());
        root.put("max_instances",  30);

        try {
            Map<String, Object> uptimeDoc = FirestoreRest.get().get("broadcast/uptime");
            if (uptimeDoc != null && uptimeDoc.get("serviceStartedAt") instanceof String s) {
                root.put("service_started_at", s);
                if (uptimeDoc.get("stoppedAt") == null) {
                    root.put("service_uptime_sec",
                        java.time.Instant.now().getEpochSecond() - java.time.Instant.parse(s).getEpochSecond());
                }
            }
        } catch (Exception ignored) {}

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

        // Database — 実接続テスト
        String dbStatus = "ok", dbValue = "Connected";
        try (Connection dbConn = Database.getConnection();
             Statement dbSt = dbConn.createStatement()) {
            dbSt.execute("SELECT 1");
        } catch (Exception e) {
            dbStatus = "err"; dbValue = "Unreachable";
        }
        addStatus(system, "Database", dbStatus, dbValue);

        // Firestore
        boolean fsOk = FirestoreRest.get().isAvailable();
        addStatus(system, "Firestore", fsOk ? "ok" : "warn", fsOk ? "Available" : "Unavailable");

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

    // Firestore の lastSeen を見て 30s 以内のインスタンス数を返す
    private int countRunningInstances() {
        if (!FirestoreRest.get().isAvailable()) return 0;
        try {
            Instant cutoff = Instant.now().minusSeconds(30);
            int count = 0;
            for (FirestoreRest.Entry e : FirestoreRest.get().list("instances")) {
                String ls = (String) e.data().get("lastSeen");
                String st = (String) e.data().get("status");
                if ("stopped".equals(st)) continue;
                if (ls != null && Instant.parse(ls).isAfter(cutoff)) {
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            logger.debug("countRunningInstances failed: {}", e.getMessage());
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

    // Escape backticks in SQL identifiers (defense-in-depth; IDENTIFIER_PATTERN already prohibits them)
    private String escapeSqlIdentifier(String identifier) {
        return identifier.replace("`", "``");
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

    private record GitHubPutResult(String commitSha, String newFileSha, boolean shaConflict) {}

    // 環境変数チェック
    private static void requireGitHubEnv() {
        String pat = System.getenv("GITHUB_PAT");
        if (pat == null || pat.isBlank()) {
            throw new IllegalStateException("GITHUB_PAT is not configured");
        }
    }

    // github GET するやつ
    private static Map<String, String> ghGetFile() throws Exception {
        requireGitHubEnv();
        String pat    = System.getenv("GITHUB_PAT");
        String owner  = System.getenv("GITHUB_OWNER");
        String repo   = System.getenv("GITHUB_REPO");
        String branch = System.getenv("GITHUB_BRANCH");
        String path   = System.getenv("GITHUB_YAML_PATH");
        String url    = "https://api.github.com/repos/" + owner + "/" + repo
                      + "/contents/" + path + "?ref=" + branch;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + pat)
            .header("Accept", "application/vnd.github.v3+json")
            .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200)
            throw new RuntimeException("GitHub GET failed: " + res.statusCode());
        Map<?,?> body   = mapper.readValue(res.body(), Map.class);
        String encoded  = (String) body.get("content");
        String sha      = (String) body.get("sha");
        String content  = new String(java.util.Base64.getMimeDecoder().decode(encoded),
                                     java.nio.charset.StandardCharsets.UTF_8);
        return Map.of("content", content, "sha", sha);
    }

    // github PUT するやつ
    private static GitHubPutResult ghPutFile(String content, String sha, String authorEmail)
            throws Exception {
        requireGitHubEnv();
        String pat    = System.getenv("GITHUB_PAT");
        String owner  = System.getenv("GITHUB_OWNER");
        String repo   = System.getenv("GITHUB_REPO");
        String branch = System.getenv("GITHUB_BRANCH");
        String path   = System.getenv("GITHUB_YAML_PATH");
        String url    = "https://api.github.com/repos/" + owner + "/" + repo
                      + "/contents/" + path;
        String encoded = java.util.Base64.getEncoder()
            .encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String bodyStr = mapper.writeValueAsString(Map.of(
            "message",   "Update routes.yaml by admin panel",
            "content",   encoded,
            "sha",       sha,
            "branch",    branch,
            "committer", Map.of(
                "name",  authorEmail.split("@")[0],
                "email", authorEmail
            )
        ));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + pat)
            .header("Accept", "application/vnd.github.v3+json")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(bodyStr)).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 409) return new GitHubPutResult(null, null, true);
        if (res.statusCode() != 200 && res.statusCode() != 201)
            throw new RuntimeException("GitHub PUT failed: " + res.statusCode());
        Map<?,?> parsed  = mapper.readValue(res.body(), Map.class);
        Map<?,?> commit  = (Map<?,?>) parsed.get("commit");
        Map<?,?> fileObj = (Map<?,?>) parsed.get("content");
        return new GitHubPutResult(
            (String) commit.get("sha"),
            (String) fileObj.get("sha"),  
            false
        );
    }

    // github actions取得
    private static Map<String, Object> ghGetLatestRun() throws Exception {
        requireGitHubEnv();
        String pat      = System.getenv("GITHUB_PAT");
        String owner    = System.getenv("GITHUB_OWNER");
        String repo     = System.getenv("GITHUB_REPO");
        String branch   = System.getenv("GITHUB_BRANCH");
        String workflow = System.getenv("GITHUB_WORKFLOW_FILE");
        if (workflow == null || workflow.isBlank()) workflow = "deploy.yml";
        // codeQLとかを除外するためのworkflow指定。
        String url = "https://api.github.com/repos/" + owner + "/" + repo
                   + "/actions/workflows/" + workflow + "/runs?branch=" + branch + "&per_page=1";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + pat)
            .header("Accept", "application/vnd.github.v3+json")
            .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200)
            throw new RuntimeException("GitHub Actions GET failed: " + res.statusCode());
        Map<?,?> parsed = mapper.readValue(res.body(), Map.class);
        @SuppressWarnings("unchecked")
        java.util.List<Map<?,?>> runs = (java.util.List<Map<?,?>>) parsed.get("workflow_runs");
        if (runs == null || runs.isEmpty())
            return Map.of("status", "none", "conclusion", "", "runUrl", "", "startedAt", "");
        Map<?,?> run = runs.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> runMap = (Map<String, Object>) run;
        // GitHub returns null (not absent) for conclusion while in-progress — Map.of rejects null values
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("status",     java.util.Objects.toString(runMap.get("status"),     "unknown"));
        result.put("conclusion", java.util.Objects.toString(runMap.get("conclusion"), ""));
        result.put("runUrl",     java.util.Objects.toString(runMap.get("html_url"),   ""));
        result.put("startedAt",  java.util.Objects.toString(runMap.get("created_at"), ""));
        return result;
    }

    // routes.yaml 読み込みとバリデーション
    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> parseAndValidateYaml(String yaml) {
        var loaderOptions = new org.yaml.snakeyaml.LoaderOptions();
        org.yaml.snakeyaml.Yaml parser = new org.yaml.snakeyaml.Yaml(
                new org.yaml.snakeyaml.constructor.SafeConstructor(loaderOptions));
        Map<?,?> doc;
        try {
            doc = parser.load(yaml);
        } catch (Exception e) {
            throw new IllegalArgumentException("YAML parse error: " + e.getMessage());
        }
        if (doc == null || !doc.containsKey("routes"))
            throw new IllegalArgumentException("Missing 'routes' key");
        java.util.List<?> rawRoutes = (java.util.List<?>) doc.get("routes");
        if (rawRoutes == null) throw new IllegalArgumentException("'routes' must be a list");

        Set<String> seenPaths = new java.util.LinkedHashSet<>();
        List<Map<String,Object>> routes = new ArrayList<>();
        for (Object r : rawRoutes) {
            if (!(r instanceof Map<?,?> m))
                throw new IllegalArgumentException("Route entry must be a map");

            String path    = (String) m.get("path");
            String table   = (String) m.get("table");
            Object colsRaw = m.get("columns");

            if (path == null || path.isBlank())
                throw new IllegalArgumentException("Route missing 'path'");
            if (!path.startsWith("/"))
                throw new IllegalArgumentException("Route path must start with '/': " + path);
            if (table == null || table.isBlank())
                throw new IllegalArgumentException("Route '" + path + "' missing 'table'");
            if (colsRaw == null)
                throw new IllegalArgumentException("Route '" + path + "' missing 'columns'");
            if (!(colsRaw instanceof List<?> cols) || cols.isEmpty())
                throw new IllegalArgumentException("Route '" + path + "' 'columns' must be a non-empty list");

            if (!seenPaths.add(path))
                throw new IllegalArgumentException("Duplicate path in routes.yaml: " + path);

            if (!YAML_IDENT.matcher(table).matches())
                throw new IllegalArgumentException("Route '" + path + "' unsafe table name: '" + table + "'");

            List<String> columns = new ArrayList<>();
            for (Object col : cols) {
                String c = String.valueOf(col);
                if (!YAML_IDENT.matcher(c).matches())
                    throw new IllegalArgumentException("Route '" + path + "' unsafe column name: '" + c + "'");
                columns.add(c);
            }

            Map<String,Object> entry = new java.util.LinkedHashMap<>();
            entry.put("path",    path);
            entry.put("table",   table);
            entry.put("columns", columns);
            routes.add(entry);
        }
        return routes;
    }

    // routes.yaml のテーブルとカラムのチェック
    private static void validateRoutesYamlDb(List<Map<String,Object>> routes, Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        for (Map<String,Object> entry : routes) {
            String path  = (String) entry.get("path");
            String table = (String) entry.get("table");
            @SuppressWarnings("unchecked")
            List<String> columns = (List<String>) entry.get("columns");

            // テーブル存在チェック
            try (ResultSet rs = meta.getTables(null, null, table, new String[]{"TABLE", "VIEW"})) {
                if (!rs.next())
                    throw new IllegalArgumentException(
                        "Route '" + path + "': table '" + table + "' does not exist in database");
            }

            // カラム存在チェック
            Set<String> existingCols = new java.util.LinkedHashSet<>();
            try (ResultSet rs = meta.getColumns(null, null, table, null)) {
                while (rs.next()) existingCols.add(rs.getString("COLUMN_NAME").toLowerCase());
            }
            for (String col : columns) {
                if (!existingCols.contains(col.toLowerCase()))
                    throw new IllegalArgumentException(
                        "Route '" + path + "': column '" + col + "' does not exist in table '" + table + "'");
            }
        }
    }

    // githubからroutes.yamlをGET
    @GetMapping("/admin/yaml/routes")
    public void getYamlRoutes(Context ctx) {
        ctx.header("Cache-Control", "no-store");
        try {
            Map<String, String> file = ghGetFile();
            ctx.json(Map.of("content", file.get("content"), "sha", file.get("sha")));
        } catch (IllegalStateException e) {
            logger.warn("getYamlRoutes: GitHub not configured: {}", e.getMessage());
            ctx.status(503).json(Map.of("error", "GitHub not configured: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("getYamlRoutes failed", e);
            ctx.status(502).json(Map.of("error", "GitHub API error"));
        }
    }

    // routes.yamlをgithubにput with 構文チェックなど
    @PutMapping("/admin/yaml/routes")
    public void putYamlRoutes(Context ctx) {
        String email = ctx.getAttribute(CfAccessAuth.ATTR_VERIFIED_EMAIL);
        if (email == null || email.isBlank()) email = "unknown@admin"; // 開発環境（CF_ACCESS_DEV_DISABLE=true）用
        try {
            @SuppressWarnings("unchecked")
            Map<?,?> body = ctx.bodyAs(Map.class);
            if (body == null) { ctx.status(400).json(Map.of("error", "Request body required")); return; }
            String content = (String) body.get("content");
            String sha     = (String) body.get("sha");
            if (content == null || sha == null) {
                ctx.status(400).json(Map.of("error", "content and sha are required"));
                return;
            }
            List<Map<String,Object>> routes = parseAndValidateYaml(content);
            try (Connection conn = Database.getConnection()) {
                validateRoutesYamlDb(routes, conn);
            }
            GitHubPutResult result = ghPutFile(content, sha, email);
            if (result.shaConflict()) {
                ctx.status(409).json(Map.of("error",
                    "Conflict: routes.yaml was modified by another user. Please reload."));
                return;
            }
            ctx.json(Map.of("commitSha", result.commitSha(), "newSha", result.newFileSha()));
        } catch (dev.gate.core.ClientErrorException ce) {
            throw ce;
        } catch (IllegalStateException e) {
            logger.warn("putYamlRoutes: GitHub not configured: {}", e.getMessage());
            ctx.status(503).json(Map.of("error", "GitHub not configured: " + e.getMessage()));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("putYamlRoutes failed", e);
            ctx.status(502).json(Map.of("error", "GitHub API error"));
        }
    }

    // github actions取得エンドポイント
    @GetMapping("/admin/yaml/status")
    public void getYamlStatus(Context ctx) {
        ctx.header("Cache-Control", "no-store");
        try {
            ctx.json(ghGetLatestRun());
        } catch (IllegalStateException e) {
            logger.warn("getYamlStatus: GitHub not configured: {}", e.getMessage());
            ctx.status(503).json(Map.of("error", "GitHub not configured: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("getYamlStatus failed", e);
            ctx.status(502).json(Map.of("error", "GitHub API error"));
        }
    }
}
