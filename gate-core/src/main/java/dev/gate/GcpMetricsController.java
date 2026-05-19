package dev.gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.core.Logger;
import dev.gate.mapping.GetMapping;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Proxies Cloud Monitoring timeseries data to the admin panel.
 * Uses the Cloud Run metadata server for authentication — no service account JSON required.
 */
@GateController
public class GcpMetricsController {

    private static final Logger      logger          = new Logger(GcpMetricsController.class);
    private static final ObjectMapper mapper          = new ObjectMapper();
    private static final HttpClient  http            = HttpClient.newHttpClient();
    private static final String      METADATA_BASE   = "http://metadata.google.internal/computeMetadata/v1/";
    private static final String      MONITORING_BASE = "https://monitoring.googleapis.com/v3/projects/";

    @GetMapping("/admin/metrics/gcp")
    public void gcpMetrics(Context ctx) {
        ctx.header("Cache-Control", "no-store");

        // Determine granularity from ?range= query param
        String rangeParam = ctx.query("range");
        final int periodSeconds, numBuckets;
        if ("1h".equals(rangeParam)) {
            periodSeconds = 300;  numBuckets = 12;   // 5-min buckets × 12 = 1h
        } else if ("6h".equals(rangeParam)) {
            periodSeconds = 600;  numBuckets = 36;   // 10-min buckets × 36 = 6h
        } else {
            periodSeconds = 3600; numBuckets = 24;   // hourly buckets × 24 = 24h (default)
        }

        // Align to period boundaries for clean bucket windows
        long nowPeriod      = Instant.now().getEpochSecond() / periodSeconds;
        Instant alignedEnd  = Instant.ofEpochSecond((nowPeriod + 1) * periodSeconds);
        Instant alignedStart = alignedEnd.minusSeconds((long) periodSeconds * numBuckets);

        try {
            String accessToken = fetchAccessToken();
            String projectId   = fetchMetadata("project/project-id").strip();
            String service     = System.getenv().getOrDefault("K_SERVICE", "");
            String periodStr   = periodSeconds + "s";

            long[]   requestCount   = queryLongMetric(accessToken, projectId, service,
                "run.googleapis.com/request_count",
                "ALIGN_SUM", "REDUCE_SUM", alignedStart, alignedEnd, periodStr, numBuckets, periodSeconds);
            long[]   instanceCount  = queryLongMetric(accessToken, projectId, service,
                "run.googleapis.com/container/instance_count",
                "ALIGN_MAX", "REDUCE_MAX", alignedStart, alignedEnd, periodStr, numBuckets, periodSeconds);
            double[] cpuUtilization = queryDoubleMetric(accessToken, projectId, service,
                "run.googleapis.com/container/cpu/utilizations",
                "ALIGN_PERCENTILE_50", "REDUCE_PERCENTILE_50", alignedStart, alignedEnd, periodStr, numBuckets, periodSeconds);

            ctx.json(buildResponse(alignedStart, alignedEnd, periodSeconds, numBuckets,
                requestCount, instanceCount, cpuUtilization));
        } catch (Exception e) {
            logger.warn("gcpMetrics unavailable (not on GCP?): {}", e.getMessage());
            ctx.json(buildEmptyResponse(alignedStart, alignedEnd, periodSeconds, numBuckets));
        }
    }

    // ── Response builders ─────────────────────────────────────────────────

    private ObjectNode buildResponse(Instant start, Instant end, int periodSeconds, int numBuckets,
                                     long[] requestCount, long[] instanceCount, double[] cpuUtilization) {
        ObjectNode root = mapper.createObjectNode();

        root.putObject("range")
            .put("from", start.toString())
            .put("to",   end.toString());

        ArrayNode reqArr   = root.putArray("requestCount");
        ArrayNode instArr  = root.putArray("instanceCount");
        ArrayNode cpuArr   = root.putObject("cpu").putArray("default");

        for (int i = 0; i < numBuckets; i++) {
            long tMs = (start.getEpochSecond() + (long)(i + 1) * periodSeconds) * 1000L;
            reqArr.addObject().put("t", tMs).put("v", requestCount[i]);
            instArr.addObject().put("t", tMs).put("v", instanceCount[i]);
            // Store as 0.0–1.0 fraction; frontend MetricsPanel multiplies ×100 for display
            cpuArr.addObject().put("t", tMs)
                              .put("v", Math.round(cpuUtilization[i] * 100000.0) / 100000.0);
        }

        root.putArray("alerts");
        return root;
    }

    private ObjectNode buildEmptyResponse(Instant start, Instant end, int periodSeconds, int numBuckets) {
        ObjectNode root = mapper.createObjectNode();

        root.putObject("range")
            .put("from", start.toString())
            .put("to",   end.toString());

        ArrayNode reqArr  = root.putArray("requestCount");
        ArrayNode instArr = root.putArray("instanceCount");
        ArrayNode cpuArr  = root.putObject("cpu").putArray("default");

        for (int i = 0; i < numBuckets; i++) {
            long tMs = (start.getEpochSecond() + (long)(i + 1) * periodSeconds) * 1000L;
            reqArr.addObject().put("t", tMs).put("v", 0);
            instArr.addObject().put("t", tMs).put("v", 0);
            cpuArr.addObject().put("t", tMs).put("v", 0.0);
        }

        root.putArray("alerts");
        return root;
    }

    // ── Cloud Monitoring queries ──────────────────────────────────────────

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
                    // DISTRIBUTION metrics (e.g. cpu/utilizations) — extract the mean
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

    // ── Metadata server ───────────────────────────────────────────────────

    private String fetchAccessToken() throws Exception {
        JsonNode node = mapper.readTree(fetchMetadata("instance/service-accounts/default/token"));
        return node.get("access_token").asText();
    }

    private String fetchMetadata(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(METADATA_BASE + path))
            .header("Metadata-Flavor", "Google")
            .GET()
            .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    // ── Util ──────────────────────────────────────────────────────────────

    private int toPeriodIndex(String endTime, long startEpochPeriod, int periodSeconds) {
        try {
            long endEpochPeriod = Instant.parse(endTime).getEpochSecond() / periodSeconds;
            return (int)(endEpochPeriod - startEpochPeriod) - 1;
        } catch (Exception e) {
            return -1;
        }
    }
}
