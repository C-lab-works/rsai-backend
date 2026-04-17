package dev.gate.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class Context {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final String path;
    private final HttpServletRequest request;
    private String responseBody = "";
    private String contentType = "text/plain";
    private final Map<String, String> headers = new HashMap<>();

    public Context(String path, HttpServletRequest request) {
        this.path = path;
        this.request = request;
    }

    public String path() {
        return path;
    }

    public String query(String key) {
        return request.getParameter(key);
    }

    public String body() {
        try {
            return request.getReader().lines().collect(Collectors.joining());
        } catch (IOException e) {
            System.err.println("readfailed: " + e.getMessage());
            return "";
        }
    }

    public void result(String body) {
        this.responseBody = body;
    }

    public void json(Object object) {
        try {
            this.responseBody = mapper.writeValueAsString(object);
            this.contentType = "application/json";
        } catch (Exception e) {
            System.err.println("serializefailed: " + e.getMessage());
            this.responseBody = "{}";
        }
    }

    public void header(String key, String value) {
        headers.put(key, value);
    }

    public String responseBody() { return responseBody; }
    public String contentType()  { return contentType; }
    public Map<String, String> headers() { return headers; }
}
