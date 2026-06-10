package dev.gate.core;

import dev.gate.mapping.WsMapping;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * WebSocket アップグレードにも before-filter（認証）が適用されることの統合テスト。
 *
 * Jetty の WS アップグレードはサーブレット service() を通らないため、
 * Gate#authorizeWsUpgrade が無いと WS ルートは無認証で公開される（その回帰防止）。
 */
public class WsUpgradeAuthTest {

    private static final String AUTH_HEADER = "X-Test-Key";
    private static final String AUTH_VALUE  = "secret";

    private static GateServer server;
    private static int port;
    private static HttpClient client;

    /** @WsMapping 経由（本番と同じ登録パス）で登録するテスト用エコーコントローラ */
    public static class EchoController {
        @WsMapping("/ws-echo")
        public void echo(WsContext ctx, String message) {
            ctx.send("echo:" + message);
        }
    }

    @BeforeAll
    static void startServer() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        Gate gate = new Gate();
        // ApiKeyAuth と同形の認証フィルタ: ヘッダ不一致なら 401 で halt
        gate.before(ctx -> {
            if (!AUTH_VALUE.equals(ctx.requestHeader(AUTH_HEADER))) {
                ctx.status(401).json(Map.of("error", "Unauthorized")).halt();
            }
        });
        gate.get("/plain", ctx -> ctx.json(Map.of("ok", true)));
        gate.register(new EchoController());
        server = gate.start(port);
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) server.stop();
    }

    @Test
    void wsUpgradeWithoutAuthIsRejected() {
        CompletableFuture<WebSocket> cf = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws-echo"), new WebSocket.Listener() {});

        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> cf.get(10, TimeUnit.SECONDS));
        WebSocketHandshakeException hse =
                assertInstanceOf(WebSocketHandshakeException.class, ex.getCause());
        assertEquals(401, hse.getResponse().statusCode());
    }

    @Test
    void wsUpgradeWithAuthSucceedsAndEchoes() throws Exception {
        CompletableFuture<String> received = new CompletableFuture<>();
        WebSocket ws = client.newWebSocketBuilder()
                .header(AUTH_HEADER, AUTH_VALUE)
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws-echo"), new WebSocket.Listener() {
                    private final StringBuilder buf = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last) received.complete(buf.toString());
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(10, TimeUnit.SECONDS);
        try {
            ws.sendText("hello", true).get(5, TimeUnit.SECONDS);
            assertEquals("echo:hello", received.get(10, TimeUnit.SECONDS));
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void httpRouteWithoutAuthIsStillRejected() throws Exception {
        HttpResponse<String> res = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/plain")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, res.statusCode());
    }
}
