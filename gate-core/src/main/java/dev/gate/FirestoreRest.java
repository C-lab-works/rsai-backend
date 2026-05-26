package dev.gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gate.core.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

// firestore rest
public class FirestoreRest {

    private static final Logger        log      = new Logger(FirestoreRest.class);
    private static final HttpClient    HTTP     = HttpClient.newHttpClient();
    private static final ObjectMapper  MAPPER   = new ObjectMapper();
    private static final String        META     = "http://metadata.google.internal/computeMetadata/v1/";
    private static final FirestoreRest INSTANCE = new FirestoreRest();

    private volatile String  docBase;      
    private volatile String  queryBase;  
    private volatile boolean available    = false;

    private final AtomicReference<String> cachedToken = new AtomicReference<>();
    private volatile Instant tokenExpiry = Instant.EPOCH;

    private FirestoreRest() {}

    public static FirestoreRest get() { return INSTANCE; }

    // 初期化
    public void init() {
        try {
            String projectId = meta("project/project-id").strip();
            String base = "https://firestore.googleapis.com/v1/projects/" + projectId
                          + "/databases/(default)/documents";
            docBase   = base + "/";
            queryBase = base;
            available = true;
            log.info("FirestoreRest ready project={}", projectId);
        } catch (Exception e) {
            log.warn("FirestoreRest unavailable (metadata server unreachable): {}", e.getMessage());
        }
    }

    public boolean isAvailable() { return available; }

    // 認証
    private String token() throws Exception {
        if (Instant.now().isBefore(tokenExpiry)) {
            String t = cachedToken.get();
            if (t != null) return t;
        }
        JsonNode node = MAPPER.readTree(meta("instance/service-accounts/default/token"));
        String token = node.get("access_token").asText();
        int exp = node.path("expires_in").asInt(3600);
        cachedToken.set(token);
        tokenExpiry = Instant.now().plusSeconds(exp - 60);
        return token;
    }

    private String meta(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(META + path))
            .header("Metadata-Flavor", "Google")
            .timeout(Duration.ofSeconds(3))
            .GET().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    public Map<String, Object> get(String path) throws Exception {
        HttpResponse<String> res = http("GET", docBase + path, null);
        if (res.statusCode() == 404) return null;
        assertOk("GET " + path, res);
        return toMap(MAPPER.readTree(res.body()));
    }

    public void set(String path, Map<String, Object> data) throws Exception {
        assertOk("SET " + path, http("PATCH", docBase + path, MAPPER.writeValueAsString(toDoc(data))));
    }

    public void update(String path, Map<String, Object> fields) throws Exception {
        StringBuilder url = new StringBuilder(docBase + path + "?");
        for (String key : fields.keySet()) {
            url.append("updateMask.fieldPaths=")
               .append(URLEncoder.encode(key, StandardCharsets.UTF_8))
               .append("&");
        }
        assertOk("UPDATE " + path, http("PATCH", url.toString(), MAPPER.writeValueAsString(toDoc(fields))));
    }

    public void add(String collectionPath, Map<String, Object> data) throws Exception {
        assertOk("ADD " + collectionPath, http("POST", docBase + collectionPath,
                MAPPER.writeValueAsString(toDoc(data))));
    }

    public void delete(String path) throws Exception {
        HttpResponse<String> res = http("DELETE", docBase + path, null);
        if (res.statusCode() == 404) return;
        assertOk("DELETE " + path, res);
    }

    public List<Entry> list(String collectionPath) throws Exception {
        HttpResponse<String> res = http("GET", docBase + collectionPath + "?pageSize=200", null);
        if (res.statusCode() == 404) return Collections.emptyList();
        assertOk("LIST " + collectionPath, res);
        JsonNode docs = MAPPER.readTree(res.body()).path("documents");
        if (!docs.isArray()) return Collections.emptyList();
        List<Entry> result = new ArrayList<>();
        for (JsonNode doc : docs) {
            String name = doc.path("name").asText();
            result.add(new Entry(name.substring(name.lastIndexOf('/') + 1), toMap(doc)));
        }
        return result;
    }

    public List<Entry> query(String parentPath, String collectionId,
                             String orderField, boolean desc, int limit) throws Exception {
        String url = parentPath.isEmpty()
            ? queryBase + ":runQuery"
            : docBase + parentPath + ":runQuery";

        ObjectNode body = MAPPER.createObjectNode();
        ObjectNode sq   = body.putObject("structuredQuery");
        sq.putArray("from").addObject()
            .put("collectionId", collectionId)
            .put("allDescendants", false);
        ObjectNode ob = sq.putArray("orderBy").addObject();
        ob.putObject("field").put("fieldPath", orderField);
        ob.put("direction", desc ? "DESCENDING" : "ASCENDING");
        sq.put("limit", limit);

        HttpResponse<String> res = http("POST", url, MAPPER.writeValueAsString(body));
        assertOk("QUERY " + collectionId, res);

        JsonNode root = MAPPER.readTree(res.body());
        List<Entry> result = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode item : root) {
                JsonNode doc = item.path("document");
                if (!doc.isObject() || !doc.has("name")) continue;
                String name = doc.path("name").asText();
                result.add(new Entry(name.substring(name.lastIndexOf('/') + 1), toMap(doc)));
            }
        }
        return result;
    }

    public record Entry(String id, Map<String, Object> data) {}

    // ── HTTP helper ───────────────────────────────────────────────────────────

    private HttpResponse<String> http(String method, String url, String jsonBody) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + token());
        if (jsonBody != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertOk(String op, HttpResponse<String> res) {
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            String snippet = res.body().substring(0, Math.min(200, res.body().length()));
            log.warn("Firestore {} → HTTP {}: {}", op, res.statusCode(), snippet);
            throw new RuntimeException("Firestore " + op + " HTTP " + res.statusCode());
        }
    }

    // ── Firestore value encoding ──────────────────────────────────────────────

    private ObjectNode toDoc(Map<String, Object> data) {
        ObjectNode doc    = MAPPER.createObjectNode();
        ObjectNode fields = doc.putObject("fields");
        data.forEach((k, v) -> fields.set(k, toValue(v)));
        return doc;
    }

    @SuppressWarnings("unchecked")
    private JsonNode toValue(Object v) {
        ObjectNode n = MAPPER.createObjectNode();
        if (v == null)              { n.putNull("nullValue");                    return n; }
        if (v instanceof String s)  { n.put("stringValue", s);                  return n; }
        if (v instanceof Long l)    { n.put("integerValue", l.toString());       return n; }
        if (v instanceof Integer i) { n.put("integerValue", i.toString());       return n; }
        if (v instanceof Double d)  { n.put("doubleValue", d);                   return n; }
        if (v instanceof Boolean b) { n.put("booleanValue", b);                  return n; }
        if (v instanceof Map m) {
            ObjectNode mv = n.putObject("mapValue");
            ObjectNode mf = mv.putObject("fields");
            ((Map<String, Object>) m).forEach((k, val) -> mf.set(k, toValue(val)));
            return n;
        }
        if (v instanceof List l) {
            ObjectNode  av   = n.putObject("arrayValue");
            ArrayNode   vals = av.putArray("values");
            ((List<Object>) l).forEach(item -> vals.add(toValue(item)));
            return n;
        }
        n.put("stringValue", v.toString());
        return n;
    }

    // ── Firestore value decoding ──────────────────────────────────────────────

    private Map<String, Object> toMap(JsonNode doc) {
        Map<String, Object> result = new LinkedHashMap<>();
        JsonNode fields = doc.path("fields");
        if (fields.isObject()) {
            fields.fields().forEachRemaining(e -> result.put(e.getKey(), fromValue(e.getValue())));
        }
        return result;
    }

    private Object fromValue(JsonNode v) {
        if (v.has("stringValue"))    return v.get("stringValue").asText();
        if (v.has("integerValue"))   return Long.parseLong(v.get("integerValue").asText());
        if (v.has("doubleValue"))    return v.get("doubleValue").asDouble();
        if (v.has("booleanValue"))   return v.get("booleanValue").asBoolean();
        if (v.has("timestampValue")) return v.get("timestampValue").asText();
        if (v.has("nullValue"))      return null;
        if (v.has("mapValue"))       return toMap(v.get("mapValue"));
        if (v.has("arrayValue")) {
            List<Object> list = new ArrayList<>();
            JsonNode vals = v.path("arrayValue").path("values");
            if (vals.isArray()) vals.forEach(e -> list.add(fromValue(e)));
            return list;
        }
        return null;
    }
}
