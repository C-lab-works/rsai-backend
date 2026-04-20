package dev.gate.core;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;

public class WsAdapter extends WebSocketAdapter {

    private static final Logger logger = new Logger(WsAdapter.class);

    private final WsHandler handler;

    public WsAdapter(WsHandler handler) {
        this.handler = handler;
    }

    @Override
    public void onWebSocketText(String message) {
        try {
            handler.handle(new WsContext(getSession()), message);
        } catch (Exception e) {
            logger.error("WebSocket handler error: " + e.getMessage(), e);
        }
    }

    @Override
    public void onWebSocketConnect(Session session) {
        super.onWebSocketConnect(session);
        logger.info("WS connected: {}", session.getRemoteAddress());
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason) {
        super.onWebSocketClose(statusCode, reason);
        logger.info("WS closed: {} {}", statusCode, reason);
    }

    @Override
    public void onWebSocketError(Throwable cause) {
        logger.error("WebSocket error: {}", cause.getMessage(), cause);
    }
}