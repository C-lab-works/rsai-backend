package dev.gate.core;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.util.concurrent.ExecutionException;

public class WsContext {

    private final Session session;

    public WsContext(Session session) {
        this.session = session;
    }

    public void send(String message) {
        try {
            Callback.Completable.with(cb -> session.sendText(message, cb)).get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new UncheckedIOException("Failed to send WebSocket message",
                    cause instanceof IOException io ? io : new IOException(cause));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException("Failed to send WebSocket message",
                    new InterruptedIOException("Interrupted while sending WebSocket message"));
        }
    }

    public boolean isOpen() {
        return session.isOpen();
    }
}
