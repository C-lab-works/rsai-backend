package dev.gate.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;

// HTTPキャッシュ系のヘルパー
public final class HttpCache {

    private HttpCache() {}

    /**
     * 事前計算済みのキャッシュ配信用エントリ（identity / gzip / ETag のセット）。
     * 背景リフレッシュ時に {@link #entryOf(byte[])} で構築し、リクエスト時は
     * {@link #serveJson(Context, Entry, String)} でゼロコピー配信する。
     */
    public record Entry(byte[] json, byte[] jsonGzip, String etag) {}

    /** JSON バイト列から配信エントリを構築する（gzip 圧縮と ETag 計算を一度だけ行う）。 */
    public static Entry entryOf(byte[] json) throws IOException {
        return new Entry(json, gzip(json), etag(json));
    }

    /**
     * キャッシュ済み JSON の共通配信。ETag / If-None-Match による 304、
     * Accept-Encoding に応じた事前圧縮 gzip の選択、Vary 付与までを一括で行う。
     * DataController / AnnouncementsController / YamlRouteLoader / CongestionController の
     * 全キャッシュルートがこのメソッドを通る（配信挙動を一箇所に集約）。
     */
    public static void serveJson(Context ctx, Entry entry, String cacheControl) {
        ctx.header("Cache-Control", cacheControl);
        ctx.header("ETag", entry.etag());
        // gzip と identity で表現が異なるため CDN/プロキシに区別させる
        ctx.header("Vary", "Accept-Encoding");
        if (entry.etag().equals(ctx.requestHeader("If-None-Match"))) {
            ctx.status(304);
            return;
        }
        String ae = ctx.requestHeader("Accept-Encoding");
        // 極小ボディは gzip ヘッダ分むしろ膨らむため、小さくなる場合だけ圧縮表現を使う
        if (ae != null && ae.contains("gzip") && entry.jsonGzip().length < entry.json().length) {
            ctx.header("Content-Encoding", "gzip").jsonBytes(entry.jsonGzip());
        } else {
            ctx.jsonBytes(entry.json());
        }
    }

    // CRC32 ベースの弱い ETag。引用符付きで返すのでヘッダ値にそのまま使える。
    public static String etag(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return "\"" + Long.toHexString(crc.getValue()) + "\"";
    }

    // gzip 圧縮。キャッシュ層が圧縮済みバイト列を保持して Content-Encoding: gzip で配信する用途。
    public static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(32, data.length / 3));
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            gos.write(data);
        }
        return baos.toByteArray();
    }
}
