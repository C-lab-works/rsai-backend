package dev.gate.core;

import org.eclipse.jetty.websocket.api.Session;
import java.io.IOException;

public class WsContext {

    private final Session session;

    public WsContext(Session session) {
        this.session = session;
    }

    public void send(String message) {
        try {
            session.getRemote().sendString(message);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean isOpen() {
        return session.isOpen();
    }
}