package dev.gate.core;

/** ルートに対応する処理を表す関数型インターフェース */
@FunctionalInterface
public interface Handler {
    void handle(Context ctx);
}
