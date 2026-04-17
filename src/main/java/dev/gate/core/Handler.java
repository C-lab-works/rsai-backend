package dev.gate.core;

@FunctionalInterface
public interface Handler {
    void handle(Context ctx);
}
