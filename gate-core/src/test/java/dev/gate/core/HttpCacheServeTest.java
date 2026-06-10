package dev.gate.core;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HttpCache.serveJson の統合テスト。
 *
 * DataController / AnnouncementsController / YamlRouteLoader / CongestionController の
 * 全キャッシュ配信ルートがこのヘルパーを通るため、ここで
 * ETag / If-None-Match 304 / 事前圧縮 gzip / Vary の挙動を一括で保証する
 * （特に /congestion は本ヘルパー導入で初めて 304・gzip 対応になった）。
 */
public class HttpCacheServeTest {

    private static final String CACHE_CONTROL = "public, max-age=30, s-maxage=30, stale-while-revalidate=60";
    // gzip の効果が出る程度に冗長な JSON
    private static final byte[] PAYLOAD =
            ("{\"data\":\"" + "festival".repeat(256) + "\"}").getBytes(StandardCharsets.UTF_8);

    // gzip 化するとヘッダ分でむしろ膨らむ極小ボディ
    private static final byte[] TINY_PAYLOAD = "[]".getBytes(StandardCharsets.UTF_8);

    private static GateServer server;
    private static int port;
    private static HttpClient client;
    private static HttpCache.Entry entry;
    private static HttpCache.Entry tinyEntry;

    @BeforeAll
    static void startServer() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        entry = HttpCache.entryOf(PAYLOAD);
        tinyEntry = HttpCache.entryOf(TINY_PAYLOAD);
        Gate gate = new Gate();
        gate.get("/cached", ctx -> HttpCache.serveJson(ctx, entry, CACHE_CONTROL));
        gate.get("/tiny",   ctx -> HttpCache.serveJson(ctx, tinyEntry, CACHE_CONTROL));
        server = gate.start(port);
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) server.stop();
    }

    private HttpRequest.Builder request() {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/cached")).GET();
    }

    @Test
    void gzipRequestIsServedFromPrecompressedCache() throws Exception {
        HttpResponse<byte[]> res = client.send(
                request().header("Accept-Encoding", "gzip").build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, res.statusCode());
        assertEquals("gzip", res.headers().firstValue("Content-Encoding").orElse(null));
        assertEquals(entry.etag(), res.headers().firstValue("ETag").orElse(null));
        assertEquals("Accept-Encoding", res.headers().firstValue("Vary").orElse(null));
        assertEquals(CACHE_CONTROL, res.headers().firstValue("Cache-Control").orElse(null));
        assertTrue(res.body().length < PAYLOAD.length, "gzip body should be smaller than identity");

        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(res.body()))) {
            assertArrayEquals(PAYLOAD, gis.readAllBytes());
        }
    }

    @Test
    void identityRequestIsServedUncompressed() throws Exception {
        // JDK HttpClient は Accept-Encoding を付けない → identity 表現が返る
        HttpResponse<byte[]> res = client.send(
                request().build(), HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, res.statusCode());
        assertTrue(res.headers().firstValue("Content-Encoding").isEmpty(),
                "identity response must not have Content-Encoding");
        assertEquals(entry.etag(), res.headers().firstValue("ETag").orElse(null));
        assertArrayEquals(PAYLOAD, res.body());
    }

    @Test
    void matchingIfNoneMatchReturns304WithoutBody() throws Exception {
        HttpResponse<byte[]> res = client.send(
                request().header("If-None-Match", entry.etag()).build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(304, res.statusCode());
        assertEquals(0, res.body().length, "304 must not carry a body");
        assertEquals(entry.etag(), res.headers().firstValue("ETag").orElse(null));
    }

    @Test
    void staleIfNoneMatchStillReturnsFullBody() throws Exception {
        HttpResponse<byte[]> res = client.send(
                request().header("If-None-Match", "\"deadbeef\"").build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, res.statusCode());
        assertArrayEquals(PAYLOAD, res.body());
    }

    @Test
    void tinyPayloadIsServedIdentityEvenWhenGzipRequested() throws Exception {
        HttpResponse<byte[]> res = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/tiny"))
                        .header("Accept-Encoding", "gzip").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, res.statusCode());
        assertTrue(res.headers().firstValue("Content-Encoding").isEmpty(),
                "gzip representation is larger for tiny bodies — identity must be served");
        assertArrayEquals(TINY_PAYLOAD, res.body());
    }
}
