package dev.gate.core;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;

public class WsAdapter extends WebSocketAdapter {

    private final WsHandle handler;

    public WsAdapter(WsHandle handler) {
        this.handler = handler;
    }

    @Override
    public void onWebSocketText(String message) {
        handler.handle(new WsContext(getSession()), message);
    }

    @Override
    public void onWebSocketConnect(Session session) {
        super.onWebSocketConnect(session);
        System.out.println("WS connected: " + session.getRemoteAddress());
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason) {
        System.out.println("WS closed: " + statusCode);
    }
}