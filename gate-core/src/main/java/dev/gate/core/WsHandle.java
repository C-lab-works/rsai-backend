package dev.gate.core;

@FunctionalInterface
public interface WsHandle {
    void handle(WsContext ctx, String message);
}