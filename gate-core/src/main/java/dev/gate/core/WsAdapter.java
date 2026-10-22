package dev.gate.core;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;

public class WsAdapter extends Session.Listener.AbstractAutoDemanding {

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
    public void onWebSocketOpen(Session session) {
        super.onWebSocketOpen(session);
        logger.info("WS connected: {}", session.getRemoteSocketAddress());
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason, Callback callback) {
        logger.info("WS closed: {} {}", statusCode, reason);
        callback.succeed();
    }

    @Override
    public void onWebSocketError(Throwable cause) {
        logger.error("WebSocket error: {}", cause.getMessage(), cause);
    }
}
