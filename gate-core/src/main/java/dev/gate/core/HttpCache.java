package dev.gate.core;

import java.util.zip.CRC32;

// HTTPキャッシュ系のヘルパー
public final class HttpCache {

    private HttpCache() {}

    // CRC32 ベースの弱い ETag。引用符付きで返すのでヘッダ値にそのまま使える。
    public static String etag(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return "\"" + Long.toHexString(crc.getValue()) + "\"";
    }
}
