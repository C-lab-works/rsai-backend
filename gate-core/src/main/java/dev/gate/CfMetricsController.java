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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;

@GateController
public class CfMetricsController {

    private static final Logger      logger         = new Logger(CfMetricsController.class);
    private static final ObjectMapper mapper         = new ObjectMapper();
    private static final HttpClient  http           = HttpClient.newHttpClient();
    private static final String      CF_GRAPHQL_URL = "https://api.cloudflare.com/client/v4/graphql";

    @GetMapping("/admin/metrics/cf")
    public void cfMetrics(Context ctx) {
        ctx.header("Cache-Control", "no-store");

        String cfApiToken = System.getenv("CF_API_TOKEN");
        String cfZoneId   = System.getenv("CF_ZONE_ID");

        if (cfApiToken == null || cfApiToken.isBlank() || cfZoneId == null || cfZoneId.isBlank()) {
            ctx.status(503).json(Map.of("error", "CF_API_TOKEN or CF_ZONE_ID not configured"));
            return;
        }

        String rangeParam = ctx.query("range");
        final int numBuckets = switch (rangeParam != null ? rangeParam : "") {
            case "1h" -> 2;
            case "6h" -> 6;
            default   -> 24;
        };

        long    nowHour      = Instant.now().getEpochSecond() / 3600;
        Instant alignedEnd   = Instant.ofEpochSecond((nowHour + 1) * 3600);
        Instant alignedStart = alignedEnd.minusSeconds((long) numBuckets * 3600);

        try {
            String   body   = buildRequestBody(cfZoneId, alignedStart, alignedEnd, numBuckets);
            JsonNode groups = executeGraphQL(cfApiToken, body);
            ctx.json(buildResponse(groups, alignedStart, numBuckets));
        } catch (Exception e) {
            logger.warn("cfMetrics failed: {}", e.getMessage());
            ctx.json(buildEmptyResponse(alignedStart, alignedEnd, numBuckets));
        }
    }

    private String buildRequestBody(String zoneId, Instant start, Instant end, int numBuckets) throws Exception {
        String gql = String.format("""
                {
                  viewer {
                    zones(filter: {zoneTag: "%s"}) {
                      httpRequests1hGroups(
                        limit: %d,
                        filter: {datetime_geq: "%s", datetime_lt: "%s"},
                        orderBy: [datetime_ASC]
                      ) {
                        dimensions { datetime }
                        sum { requests cachedRequests bytes threats }
                      }
                    }
                  }
                }
                """, zoneId, numBuckets + 1, start, end);
        ObjectNode body = mapper.createObjectNode();
        body.put("query", gql);
        return mapper.writeValueAsString(body);
    }

    private JsonNode executeGraphQL(String apiToken, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(CF_GRAPHQL_URL))
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            logger.warn("CF GraphQL HTTP {}: {}", res.statusCode(),
                    res.body().substring(0, Math.min(300, res.body().length())));
            throw new RuntimeException("CF API returned HTTP " + res.statusCode());
        }
        JsonNode root   = mapper.readTree(res.body());
        JsonNode errors = root.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            logger.warn("CF GraphQL errors: {}", errors);
            throw new RuntimeException("CF GraphQL returned errors");
        }
        return root.path("data").path("viewer").path("zones").path(0).path("httpRequests1hGroups");
    }

    private ObjectNode buildResponse(JsonNode groups, Instant start, int numBuckets) {
        long[] requests = new long[numBuckets];
        long[] cached   = new long[numBuckets];
        long[] bytes    = new long[numBuckets];
        long[] threats  = new long[numBuckets];
        long startEpoch = start.getEpochSecond();

        if (groups.isArray()) {
            for (JsonNode g : groups) {
                String dt = g.path("dimensions").path("datetime").asText();
                try {
                    int idx = (int)((Instant.parse(dt).getEpochSecond() - startEpoch) / 3600);
                    if (idx < 0 || idx >= numBuckets) continue;
                    JsonNode sum = g.path("sum");
                    requests[idx] = sum.path("requests").asLong();
                    cached[idx]   = sum.path("cachedRequests").asLong();
                    bytes[idx]    = sum.path("bytes").asLong();
                    threats[idx]  = sum.path("threats").asLong();
                } catch (Exception ignored) {}
            }
        }

        ObjectNode root     = mapper.createObjectNode();
        root.putObject("range")
            .put("from", start.toString())
            .put("to",   Instant.ofEpochSecond(start.getEpochSecond() + (long) numBuckets * 3600).toString());
        ArrayNode  reqArr   = root.putArray("request_count");
        ArrayNode  cacheArr = root.putArray("cache_hit_rate");
        ArrayNode  bwArr    = root.putArray("bandwidth");
        ArrayNode  thrArr   = root.putArray("threats");

        for (int i = 0; i < numBuckets; i++) {
            long   tMs     = (startEpoch + (long)(i + 1) * 3600) * 1000L;
            double hitRate = requests[i] > 0
                    ? Math.round(cached[i] * 10000.0 / requests[i]) / 100.0
                    : 0.0;
            reqArr  .addObject().put("t", tMs).put("v", requests[i]);
            cacheArr.addObject().put("t", tMs).put("v", hitRate);
            bwArr   .addObject().put("t", tMs).put("v", bytes[i]);
            thrArr  .addObject().put("t", tMs).put("v", threats[i]);
        }
        return root;
    }

    private ObjectNode buildEmptyResponse(Instant start, Instant end, int numBuckets) {
        ObjectNode root     = mapper.createObjectNode();
        root.putObject("range").put("from", start.toString()).put("to", end.toString());
        ArrayNode  reqArr   = root.putArray("request_count");
        ArrayNode  cacheArr = root.putArray("cache_hit_rate");
        ArrayNode  bwArr    = root.putArray("bandwidth");
        ArrayNode  thrArr   = root.putArray("threats");
        long startEpoch = start.getEpochSecond();
        for (int i = 0; i < numBuckets; i++) {
            long tMs = (startEpoch + (long)(i + 1) * 3600) * 1000L;
            reqArr  .addObject().put("t", tMs).put("v", 0);
            cacheArr.addObject().put("t", tMs).put("v", 0.0);
            bwArr   .addObject().put("t", tMs).put("v", 0);
            thrArr  .addObject().put("t", tMs).put("v", 0);
        }
        return root;
    }
}
