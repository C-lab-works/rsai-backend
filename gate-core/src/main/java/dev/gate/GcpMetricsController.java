package dev.gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.core.Logger;
import dev.gate.core.TtlCache;
import dev.gate.mapping.GetMapping;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.Future;

/**
 * Cloud MonitoringのTimeseriesデータをAdminパネルにプロキシする。
 * 認証にはCloud Runメタデータサーバーを使用するため、サービスアカウントJSONは不要。
 */
@GateController
public class GcpMetricsController {

    private static final Logger      logger          = new Logger(GcpMetricsController.class);
    private static final ObjectMapper mapper          = dev.gate.core.Json.MAPPER;
    private static final HttpClient  http            = dev.gate.core.Http.CLIENT;
    private static final String      METADATA_BASE   = "http://metadata.google.internal/computeMetadata/v1/";
    private static final String      MONITORING_BASE = "https://monitoring.googleapis.com/v3/projects/";

    // projectId はインスタンス起動後に変わらないため 1 回だけ取得してキャッシュする（#10b）
    private static volatile String cachedProjectId = null;

    // range パラメータをキーとして 15 秒間レスポンスをキャッシュする
    private final TtlCache<ObjectNode> responseCache = new TtlCache<>(15_000L);

    @GetMapping("/admin/metrics/gcp")
    public void gcpMetrics(Context ctx) {
        ctx.header("Cache-Control", "no-store");

        // ?range= でグラニュラリティを決定
        String rangeParam = ctx.query("range");
        final int periodSeconds, numBuckets;
        if ("1h".equals(rangeParam)) {
            periodSeconds = 300;  numBuckets = 12;   // 5分バケット × 12 = 1h
        } else if ("6h".equals(rangeParam)) {
            periodSeconds = 600;  numBuckets = 36;   // 10分バケット × 36 = 6h
        } else {
            periodSeconds = 3600; numBuckets = 24;   // 1時間バケット × 24 = 24h（デフォルト）
        }

        // バケット境界に揃える
        long nowPeriod       = Instant.now().getEpochSecond() / periodSeconds;
        Instant alignedEnd   = Instant.ofEpochSecond((nowPeriod + 1) * periodSeconds);
        Instant alignedStart = alignedEnd.minusSeconds((long) periodSeconds * numBuckets);

        // TTL キャッシュキーは range パラメータ（null の場合は "24h" 扱い）
        String cacheKey = rangeParam != null ? rangeParam : "24h";

        try {
            ObjectNode response = responseCache.get(cacheKey, () -> buildGcpResponse(
                    alignedStart, alignedEnd, periodSeconds, numBuckets));
            ctx.json(response);
        } catch (Exception e) {
            logger.warn("gcpMetrics unavailable (not on GCP?): {}", e.getMessage());
            ArrayNode fallback = buildAlertsFromMemory(alignedStart, alignedEnd);
            ctx.json(buildEmptyResponse(alignedStart, alignedEnd, periodSeconds, numBuckets, fallback));
        }
    }

    /** GCP Monitoring から実際にデータを取得してレスポンスを組み立てる。キャッシュのローダーとして使用。 */
    private ObjectNode buildGcpResponse(Instant alignedStart, Instant alignedEnd,
                                        int periodSeconds, int numBuckets) throws Exception {
        // FirestoreRest の共有トークンキャッシュを再利用する（#10a）
        String accessToken = FirestoreRest.accessToken();
        // projectId はキャッシュ済み。未取得なら 1 回だけメタデータサーバーに問い合わせる（#10b）
        if (cachedProjectId == null) {
            synchronized (GcpMetricsController.class) {
                if (cachedProjectId == null) {
                    cachedProjectId = fetchMetadata("project/project-id").strip();
                }
            }
        }
        String projectId = cachedProjectId;
        String service   = System.getenv().getOrDefault("K_SERVICE", "");
        String periodStr = periodSeconds + "s";

        // 3 つの Monitoring クエリを並列実行する（#10c）
        final Instant fStart     = alignedStart;
        final Instant fEnd       = alignedEnd;
        final int fNumBuckets    = numBuckets;
        final int fPeriodSec     = periodSeconds;

        Future<ArrayNode> alertsFuture = Main.bg.submit(
            () -> queryAlerts(accessToken, projectId, fStart, fEnd));
        Future<long[]> instanceFuture = Main.bg.submit(
            () -> queryLongMetric(accessToken, projectId, service,
                "run.googleapis.com/container/instance_count",
                "ALIGN_MAX", "REDUCE_MAX", fStart, fEnd, periodStr, fNumBuckets, fPeriodSec));
        Future<double[]> cpuFuture = Main.bg.submit(
            () -> queryDoubleMetric(accessToken, projectId, service,
                "run.googleapis.com/container/cpu/utilizations",
                "ALIGN_PERCENTILE_50", "REDUCE_PERCENTILE_50", fStart, fEnd, periodStr, fNumBuckets, fPeriodSec));

        // 結果を収集。各クエリが失敗してもレスポンス形状を変えない（フォールバックで 0 埋め）
        ArrayNode alertsResult;
        try { alertsResult = alertsFuture.get(); }
        catch (Exception e) { logger.warn("queryAlerts failed: {}", e.getMessage()); alertsResult = buildAlertsFromMemory(fStart, fEnd); }

        long[] instanceCount;
        try { instanceCount = instanceFuture.get(); }
        catch (Exception e) { logger.warn("queryLongMetric failed: {}", e.getMessage()); instanceCount = new long[fNumBuckets]; }

        double[] cpuUtilization;
        try { cpuUtilization = cpuFuture.get(); }
        catch (Exception e) { logger.warn("queryDoubleMetric failed: {}", e.getMessage()); cpuUtilization = new double[fNumBuckets]; }

        return buildResponse(alignedStart, alignedEnd, periodSeconds, numBuckets,
            instanceCount, cpuUtilization, alertsResult);
    }

    // レスポンス構築

    private ObjectNode buildResponse(Instant start, Instant end, int periodSeconds, int numBuckets,
                                     long[] instanceCount, double[] cpuUtilization, ArrayNode alerts) {
        ObjectNode root = mapper.createObjectNode();

        root.putObject("range")
            .put("from", start.toString())
            .put("to",   end.toString());

        ArrayNode instArr = root.putArray("instance_count");
        ArrayNode cpuArr  = root.putObject("cpu").putArray("default");

        for (int i = 0; i < numBuckets; i++) {
            long tMs = (start.getEpochSecond() + (long)(i + 1) * periodSeconds) * 1000L;
            instArr.addObject().put("t", tMs).put("v", instanceCount[i]);
            cpuArr.addObject().put("t", tMs)
                              .put("v", Math.round(cpuUtilization[i] * 100000.0) / 100000.0);
        }

        root.set("alerts", alerts);
        return root;
    }

    private ObjectNode buildEmptyResponse(Instant start, Instant end, int periodSeconds, int numBuckets,
                                          ArrayNode alerts) {
        ObjectNode root = mapper.createObjectNode();

        root.putObject("range")
            .put("from", start.toString())
            .put("to",   end.toString());

        ArrayNode instArr = root.putArray("instance_count");
        ArrayNode cpuArr  = root.putObject("cpu").putArray("default");

        for (int i = 0; i < numBuckets; i++) {
            long tMs = (start.getEpochSecond() + (long)(i + 1) * periodSeconds) * 1000L;
            instArr.addObject().put("t", tMs).put("v", 0);
            cpuArr.addObject().put("t", tMs).put("v", 0.0);
        }

        root.set("alerts", alerts);
        return root;
    }

    // Cloud Monitoringクエリ

    private long[] queryLongMetric(String token, String projectId, String service,
                                   String metricType, String aligner, String reducer,
                                   Instant start, Instant end, String periodStr,
                                   int numBuckets, int periodSeconds) throws Exception {
        JsonNode timeSeries = fetchTimeSeries(token, projectId, service, metricType,
                                             aligner, reducer, start, end, periodStr);
        long[] result = new long[numBuckets];
        if (timeSeries == null || !timeSeries.isArray()) return result;

        long startEpochPeriod = start.getEpochSecond() / periodSeconds;
        for (JsonNode series : timeSeries) {
            for (JsonNode point : series.path("points")) {
                int idx = toPeriodIndex(point.path("interval").path("endTime").asText(),
                                        startEpochPeriod, periodSeconds);
                if (idx < 0 || idx >= numBuckets) continue;
                JsonNode val = point.path("value");
                result[idx] += val.has("int64Value")  ? val.get("int64Value").asLong()
                             : val.has("doubleValue") ? (long) val.get("doubleValue").asDouble()
                             : 0L;
            }
        }
        return result;
    }

    private double[] queryDoubleMetric(String token, String projectId, String service,
                                       String metricType, String aligner, String reducer,
                                       Instant start, Instant end, String periodStr,
                                       int numBuckets, int periodSeconds) throws Exception {
        JsonNode timeSeries = fetchTimeSeries(token, projectId, service, metricType,
                                             aligner, reducer, start, end, periodStr);
        double[] result = new double[numBuckets];
        int[]    counts = new int[numBuckets];
        if (timeSeries == null || !timeSeries.isArray()) return result;

        long startEpochPeriod = start.getEpochSecond() / periodSeconds;
        for (JsonNode series : timeSeries) {
            for (JsonNode point : series.path("points")) {
                int idx = toPeriodIndex(point.path("interval").path("endTime").asText(),
                                        startEpochPeriod, periodSeconds);
                if (idx < 0 || idx >= numBuckets) continue;
                JsonNode val = point.path("value");
                double v;
                if (val.has("doubleValue")) {
                    v = val.get("doubleValue").asDouble();
                } else if (val.has("int64Value")) {
                    v = val.get("int64Value").asDouble();
                } else if (val.has("distributionValue")) {
                    // DISTRIBUTIONメトリクス（cpu/utilizationsなど）は平均値を使用
                    v = val.get("distributionValue").path("mean").asDouble();
                } else {
                    v = 0.0;
                }
                result[idx] += v;
                counts[idx]++;
            }
        }
        for (int i = 0; i < numBuckets; i++) {
            if (counts[i] > 1) result[i] /= counts[i];
        }
        return result;
    }

    private JsonNode fetchTimeSeries(String token, String projectId, String service,
                                     String metricType, String aligner, String reducer,
                                     Instant start, Instant end, String periodStr) throws Exception {
        String serviceClause = service.isBlank() ? ""
            : " AND resource.labels.service_name=\"" + service + "\"";
        String filter = "metric.type=\"" + metricType + "\"" + serviceClause;

        String url = MONITORING_BASE + projectId + "/timeSeries"
            + "?filter="                         + URLEncoder.encode(filter, StandardCharsets.UTF_8)
            + "&interval.startTime="             + start
            + "&interval.endTime="               + end
            + "&aggregation.alignmentPeriod="    + periodStr
            + "&aggregation.perSeriesAligner="   + aligner
            + "&aggregation.crossSeriesReducer=" + reducer
            + "&aggregation.groupByFields=resource.labels.service_name";

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (res.statusCode() != 200) {
            String snippet = res.body().substring(0, Math.min(300, res.body().length()));
            logger.warn("Cloud Monitoring {} → HTTP {}: {}", metricType, res.statusCode(), snippet);
            return null;
        }
        return mapper.readTree(res.body()).path("timeSeries");
    }

    // Cloud Logging (rsai-alerts バケット) からアラートを取得
    // 失敗時は in-memory バッファにフォールバック

    private ArrayNode queryAlerts(String token, String projectId, Instant start, Instant end) {
        try {
            String filter = "resource.type=\"cloud_run_revision\""
                + " AND (httpRequest.status=503 OR httpRequest.status=429)"
                + " AND timestamp>=\"" + start + "\""
                + " AND timestamp<=\"" + end + "\"";

            ObjectNode body = mapper.createObjectNode();
            body.put("filter", filter);
            body.put("orderBy", "timestamp desc");
            body.put("pageSize", 50);
            body.putArray("resourceNames")
                .add("projects/" + projectId);

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://logging.googleapis.com/v2/entries:list"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                logger.warn("rsai-alerts query HTTP {}: {}", res.statusCode(),
                    res.body().substring(0, Math.min(200, res.body().length())));
                return buildAlertsFromMemory(start, end);
            }

            ArrayNode alerts = mapper.createArrayNode();
            JsonNode entries = mapper.readTree(res.body()).path("entries");
            if (!entries.isArray()) return alerts;

            for (JsonNode entry : entries) {
                JsonNode httpReq = entry.path("httpRequest");
                if (httpReq.isMissingNode()) continue;
                int status = httpReq.path("status").asInt(0);
                if (status < 400) continue;

                ObjectNode alert = alerts.addObject();
                alert.put("timestamp", entry.path("timestamp").asText());
                alert.put("severity",  status >= 500 ? "error" : "warn");
                alert.put("method",    httpReq.path("requestMethod").asText());
                alert.put("status",    status);
                alert.put("url",       httpReq.path("requestUrl").asText());

                JsonNode latencyNode = httpReq.path("latency");
                String latency = (!latencyNode.isMissingNode() && !latencyNode.isNull()) ? latencyNode.asText() : null;
                if (latency != null && latency.endsWith("s")) {
                    try {
                        double secs = Double.parseDouble(latency.substring(0, latency.length() - 1));
                        alert.put("durationMs", (long)(secs * 1000));
                    } catch (NumberFormatException ignored) {}
                }
            }
            return alerts;
        } catch (Exception e) {
            logger.warn("queryAlerts failed, using in-memory: {}", e.getMessage());
            return buildAlertsFromMemory(start, end);
        }
    }

    private ArrayNode buildAlertsFromMemory(Instant start, Instant end) {
        ArrayNode alerts = mapper.createArrayNode();
        for (RequestMetrics.ErrorEntry e : RequestMetrics.get().getRecentErrors(start, end)) {
            ObjectNode alert = alerts.addObject();
            alert.put("timestamp",  e.timestamp());
            alert.put("severity",   e.status() >= 500 ? "error" : "warn");
            alert.put("method",     e.method());
            alert.put("status",     e.status());
            alert.put("url",        e.path());
            if (e.durationMs() >= 0) alert.put("durationMs", e.durationMs());
        }
        return alerts;
    }

    // メタデータサーバー

    private String fetchMetadata(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(METADATA_BASE + path))
            .header("Metadata-Flavor", "Google")
            .GET()
            .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    // util

    private int toPeriodIndex(String endTime, long startEpochPeriod, int periodSeconds) {
        try {
            long endEpochPeriod = Instant.parse(endTime).getEpochSecond() / periodSeconds;
            return (int)(endEpochPeriod - startEpochPeriod) - 1;
        } catch (Exception e) {
            return -1;
        }
    }
}
