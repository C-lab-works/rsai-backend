package dev.gate.core;

@FunctionalInterface
public interface WsHandler {
    void handle(WsContext ctx, String message);
}
