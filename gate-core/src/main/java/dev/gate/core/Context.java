package dev.gate.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class Context {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Logger logger = new Logger(Context.class);
    static final int MAX_BODY_SIZE = 1024 * 1024; // 1 MB default

    private final String path;
    private final HttpServletRequest request;
    private String responseBody = "";
    private String contentType = "text/plain; charset=utf-8";
    private int statusCode = 200;
    private final Map<String, String> headers = new HashMap<>();
    private Map<String, String> pathParams = Map.of();
    private String cachedBody = null;

    public Context(String path, HttpServletRequest request) {
        this.path = path;
        this.request = request;
    }

    public String path() { return path; }

    public String method() { return request.getMethod(); }

    public String pathParam(String name) { return pathParams.get(name); }

    void setPathParams(Map<String, String> params) { this.pathParams = params; }

    public String query(String key) { return request.getParameter(key); }

    public String body() {
        if (cachedBody != null) return cachedBody;
        try {
            int contentLength = request.getContentLength();
            if (contentLength > MAX_BODY_SIZE) {
                throw new RuntimeException("Request body too large: " + contentLength + " bytes (max: " + MAX_BODY_SIZE + ")");
            }
            Charset charset = request.getCharacterEncoding() != null
                    ? Charset.forName(request.getCharacterEncoding())
                    : StandardCharsets.UTF_8;
            byte[] bytes = request.getInputStream().readAllBytes();
            if (bytes.length > MAX_BODY_SIZE) {
                throw new RuntimeException("Request body too large: " + bytes.length + " bytes (max: " + MAX_BODY_SIZE + ")");
            }
            cachedBody = new String(bytes, charset);
            return cachedBody;
        } catch (IOException e) {
            logger.error("Failed to read request body: " + e.getMessage(), e);
            return "";
        }
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

    public Context status(int code) { this.statusCode = code; return this; }
    public int statusCode() { return statusCode; }

    public Context result(String body) { this.responseBody = body; return this; }

    public Context json(Object object) {
        try {
            this.responseBody = mapper.writeValueAsString(object);
            this.contentType = "application/json; charset=utf-8";
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize response: " + e.getMessage(), e);
        }
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

    public String responseBody() { return responseBody; }
    public String contentType() { return contentType; }
    public Map<String, String> headers() { return Map.copyOf(headers); }
}
