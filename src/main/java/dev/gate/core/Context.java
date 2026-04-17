package dev.gate.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/** 1リクエストの情報とレスポンスの構築を保持するオブジェクト */
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

    /** リクエストパスを返す */
    public String path() {
        return path;
    }

    /** クエリパラメーターを取得する（例: ?name=foo → query("name") == "foo"） */
    public String query(String key) {
        return request.getParameter(key);
    }

    /** リクエストボディを文字列で返す */
    public String body() {
        try {
            return request.getReader().lines().reduce("", String::concat);
        } catch (Exception e) {
            return "";
        }
    }

    /** テキストレスポンスをセットする */
    public void result(String body) {
        this.responseBody = body;
    }

    /** オブジェクトを JSON にシリアライズしてレスポンスにセットする */
    public void json(Object object) {
        try {
            this.responseBody = mapper.writeValueAsString(object);
            this.contentType = "application/json";
        } catch (Exception e) {
            this.responseBody = "{}";
        }
    }

    /** レスポンスヘッダーを追加する */
    public void header(String key, String value) {
        headers.put(key, value);
    }

    public String responseBody() { return responseBody; }
    public String contentType()  { return contentType; }
    public Map<String, String> headers() { return headers; }
}
