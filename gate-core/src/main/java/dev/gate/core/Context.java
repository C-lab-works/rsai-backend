package dev.gate.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class Context {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Logger logger = new Logger(Context.class);
    private static final byte[] EMPTY_BYTES = new byte[0];
    static final int MAX_BODY_SIZE = 1024 * 1024; // 1 MB default

    private final String path;
    private final HttpServletRequest request;
    // レスポンスボディは byte[] で保持し、PrintWriter経由のString→char[]→byte[]の二重変換を避ける。
    // これによりGraalVM Native Imageでもサーブレットへ直接write(byte[])でき、CPU/メモリコピーを削減できる。
    private byte[] responseBodyBytes = EMPTY_BYTES;
    private String contentType = "text/plain; charset=utf-8";
    private int statusCode = 200;
    private final Map<String, String> headers = new HashMap<>();
    private Map<String, String> pathParams = Map.of();
    private Map<String, Object> attributes = null;
    private String cachedBody = null;
    private boolean halted = false;

    public Context(String path, HttpServletRequest request) {
        this.path = path;
        this.request = request;
    }

    public String path() { return path; }

    public String method() { return request.getMethod(); }

    public String pathParam(String name) { return pathParams.get(name); }

    void setPathParams(Map<String, String> params) { this.pathParams = params; }

    public String query(String key) { return request.getParameter(key); }

    public String requestHeader(String name) { return request.getHeader(name); }

    public String body() {
        if (cachedBody != null) return cachedBody;
        try {
            int contentLength = request.getContentLength();
            if (contentLength > MAX_BODY_SIZE) {
                throw new RuntimeException("Request body too large: " + contentLength + " bytes (max: " + MAX_BODY_SIZE + ")");
            }
            // 99% の POST リクは UTF-8 または charset 未指定なので、
            // Charset.forName() (内部でロックと HashMap 検索) を避ける。
            String enc = request.getCharacterEncoding();
            Charset charset = resolveCharset(enc);
            // readNBytes caps reads at MAX_BODY_SIZE+1, preventing OOM on chunked transfers
            byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_SIZE + 1);
            if (bytes.length > MAX_BODY_SIZE) {
                throw new RuntimeException("Request body too large (max: " + MAX_BODY_SIZE + " bytes)");
            }
            cachedBody = new String(bytes, charset);
            return cachedBody;
        } catch (IOException e) {
            logger.error("Failed to read request body: " + e.getMessage(), e);
            return "";
        }
    }

    /**
     * ホットパスの Charset 解決。よく見る名前は定数キャッシュし、Charset.forName() の
     * 内部 HashMap 検索を避ける。リクエストの大部分は UTF-8 または未指定。
     */
    private static Charset resolveCharset(String enc) {
        if (enc == null) return StandardCharsets.UTF_8;
        // 長さ 5 ("UTF-8") を最も頻繁ケースとして先にひっかける。
        if (enc.equalsIgnoreCase("UTF-8") || enc.equalsIgnoreCase("utf8")) return StandardCharsets.UTF_8;
        if (enc.equalsIgnoreCase("ISO-8859-1")) return StandardCharsets.ISO_8859_1;
        if (enc.equalsIgnoreCase("US-ASCII"))   return StandardCharsets.US_ASCII;
        return Charset.forName(enc);
    }

    public <T> T bodyAs(Class<T> type) {
        String raw = body();
        if (raw.isEmpty()) return null;
        try {
            return mapper.readValue(raw, type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse request body as " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    public Context halt() { this.halted = true; return this; }
    public boolean isHalted() { return halted; }

    public Context status(int code) { this.statusCode = code; return this; }
    public int statusCode() { return statusCode; }

    public Context result(String body) {
        this.responseBodyBytes = body == null ? EMPTY_BYTES : body.getBytes(StandardCharsets.UTF_8);
        return this;
    }

    public Context json(Object object) {
        try {
            this.responseBodyBytes = mapper.writeValueAsBytes(object);
            this.contentType = "application/json; charset=utf-8";
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize response: " + e.getMessage(), e);
        }
        return this;
    }

    public Context jsonRaw(String json) {
        this.responseBodyBytes = json == null ? EMPTY_BYTES : json.getBytes(StandardCharsets.UTF_8);
        this.contentType = "application/json; charset=utf-8";
        return this;
    }

    /**
     * 既にUTF-8でシリアライズ済みのバイト列を直接渡す。
     * キャッシュ層が writeValueAsBytes の結果を保持する場合に最も効率的。
     * 引数の配列はそのまま保持するため、呼び出し側で書き換えてはならない。
     */
    public Context jsonBytes(byte[] json) {
        this.responseBodyBytes = json == null ? EMPTY_BYTES : json;
        this.contentType = "application/json; charset=utf-8";
        return this;
    }

    public Context header(String key, String value) {
        if (key == null || key.contains("\r") || key.contains("\n") ||
            value == null || value.contains("\r") || value.contains("\n")) {
            throw new IllegalArgumentException("Header contains illegal characters");
        }
        headers.put(key, value);
        return this;
    }

    /**
     * フレームワーク内部用。補足化された「検証済み・定数」ヘッダー群をバリデーションなしで一括ポットする。
     * SecurityHeaders など、キー/値がコンパイル時定数で明らかに安全なケースだけ使うこと。
     * 多いホットパス (7000 req/s) で 6回/リク の validation を省くための最適化。
     */
    public Context addTrustedHeaders(Map<String, String> trusted) {
        headers.putAll(trusted);
        return this;
    }

    /**
     * 後方互換用。可能なら responseBodyBytes() を使うこと。
     */
    public String responseBody() {
        return new String(responseBodyBytes, StandardCharsets.UTF_8);
    }

    public byte[] responseBodyBytes() { return responseBodyBytes; }
    public String contentType() { return contentType; }
    public Map<String, String> headers() { return Collections.unmodifiableMap(headers); }

    public void setAttribute(String key, Object value) {
        if (attributes == null) attributes = new HashMap<>();
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return attributes != null ? (T) attributes.get(key) : null;
    }
}
