package dev.gate.core;

/**
 * クライアント起因のエラーを表す例外。4xx ステータスにマップされ、
 * サーバー側の問題とは区別して Discord アラートを発火させない。
 * Context のボディサイズ超過（413）など、リクエスト解析段階で投げられる。
 */
public final class ClientErrorException extends RuntimeException {

    private final int status;

    public ClientErrorException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** HTTP ステータスコード（4xx）。 */
    public int status() {
        return status;
    }
}
