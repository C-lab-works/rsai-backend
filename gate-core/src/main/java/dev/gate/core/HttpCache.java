package dev.gate.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;

// HTTPキャッシュ系のヘルパー
public final class HttpCache {

    private HttpCache() {}

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
