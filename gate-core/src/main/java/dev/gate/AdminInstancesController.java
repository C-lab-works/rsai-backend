package dev.gate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.core.Logger;
import dev.gate.mapping.DeleteMapping;
import dev.gate.mapping.GetMapping;
import dev.gate.mapping.PostMapping;

// ── インスタンス管理 ────────────────────────────────────────────────────────────
@GateController
public class AdminInstancesController {

    private static final Logger       logger              = new Logger(AdminInstancesController.class);
    private static final ObjectMapper mapper              = dev.gate.core.Json.MAPPER;

    // インスタンスコマンドホワイトリスト
    private static final Set<String> ALLOWED_INSTANCE_COMMANDS = Set.of(
        "ping", "cpu", "heap", "thread-count", "gc",
        "cache-stats", "logs", "log-level", "stop"
    );

    // instanceId 検証パターン（Cloud Run の HOSTNAME は英数字とハイフンのみ）
    private static final Pattern INSTANCE_ID_PATTERN = Pattern.compile("[a-zA-Z0-9_-]{1,256}");
    private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** インスタンスのステータス付きビュー（listInstances / listInstancesMetrics 共通）。 */
    record InstanceView(String id, String revision, String host, String startedAt, String status) {}

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
     * - lastSeen が 30 秒以上前の非 stopped はビューから除外する。
     * - 非 stopped で lastSeen が 3600 秒超の残骸、および
     *   stopped で lastSeen（または startedAt）が 86400 秒超の古いドキュメントは
     *   非同期で削除する（応答はブロックしない）。
     */
    static List<InstanceView> deriveInstanceViews(Instant now) throws Exception {
        List<InstanceView> views = new ArrayList<>();
        // 非同期削除対象の id を収集するリスト
        List<String> toDelete = new ArrayList<>();

        for (FirestoreRest.Entry entry : FirestoreRest.get().list("instances")) {
            Map<String, Object> d   = entry.data();
            String rawStatus        = (String) d.get("status");
            String lastSeenStr      = (String) d.get("lastSeen");

            String status;
            if ("stopped".equals(rawStatus)) {
                // stopped: lastSeen または startedAt が 86400 秒超なら削除対象
                String ageRef = lastSeenStr != null ? lastSeenStr : (String) d.get("startedAt");
                if (ageRef != null) {
                    long age = Duration.between(Instant.parse(ageRef), now).toSeconds();
                    if (age >= 86400) {
                        toDelete.add(entry.id());
                        continue;
                    }
                } else {
                    // 時刻が一切ない stopped は削除対象
                    toDelete.add(entry.id());
                    continue;
                }
                status = "stopped";
            } else if (lastSeenStr != null) {
                long age = Duration.between(Instant.parse(lastSeenStr), now).toSeconds();
                if (age >= 3600) {
                    // 1時間超の残骸: 削除対象（ビューからも除外）
                    toDelete.add(entry.id());
                    continue;
                }
                if (age >= 30) continue; // 30s〜3600s はスキップのみ
                status = age < 10 ? "running" : "degraded";
            } else {
                // lastSeen なし非 stopped: startedAt が 300 秒超なら削除対象に統合
                String startedAt = (String) d.get("startedAt");
                if (startedAt != null) {
                    long sinceStart = Duration.between(Instant.parse(startedAt), now).toSeconds();
                    if (sinceStart >= 300) {
                        toDelete.add(entry.id());
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

        // 削除対象がある場合は 1 タスクにまとめて非同期実行する
        if (!toDelete.isEmpty()) {
            final List<String> ids = List.copyOf(toDelete);
            Main.bg.submit(() -> {
                int deleted = 0;
                for (String id : ids) {
                    try {
                        FirestoreRest.get().delete("instances/" + id);
                        deleted++;
                    } catch (Exception ignored) {
                        // 個別失敗は無視して続行
                    }
                }
                logger.info("残骸インスタンス削除完了: {}件", deleted);
            });
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
            Main.bg.submit(() -> {
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
}
