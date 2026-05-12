package dev.gate.core;

import org.eclipse.jetty.websocket.api.Session;
import java.io.IOException;
import java.io.UncheckedIOException;

public class WsContext {

    private final Session session;

    public WsContext(Session session) {
        this.session = session;
    }

    public void send(String message) {
        try {
            session.getRemote().sendString(message);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to send WebSocket message", e);
        }
    }

    public boolean isOpen() {
        return session.isOpen();
    }
}