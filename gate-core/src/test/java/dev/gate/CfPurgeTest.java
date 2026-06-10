package dev.gate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CfPurge の純関数部(URL 正規化・purge ボディ構築)のユニットテスト。 */
public class CfPurgeTest {

    @Test
    void normalizeBaseUrlAddsSchemeAndStripsTrailingSlashes() {
        assertEquals("https://v2.r-sai2026.site", CfPurge.normalizeBaseUrl("v2.r-sai2026.site/"));
        assertEquals("https://example.jp", CfPurge.normalizeBaseUrl("https://example.jp"));
        assertEquals("http://localhost:8082", CfPurge.normalizeBaseUrl("http://localhost:8082///"));
        assertEquals("https://example.jp", CfPurge.normalizeBaseUrl("  example.jp  "));
        assertNull(CfPurge.normalizeBaseUrl(null));
        assertNull(CfPurge.normalizeBaseUrl("   "));
    }

    @Test
    void buildUrlsJoinsBaseAndPathsSkippingBlanks() {
        List<String> urls = CfPurge.buildUrls("https://example.jp", "/congestion", "events", null, " ");
        assertEquals(List.of("https://example.jp/congestion", "https://example.jp/events"), urls);
    }

    @Test
    void buildUrlsReturnsEmptyForNoValidPaths() {
        assertTrue(CfPurge.buildUrls("https://example.jp").isEmpty());
    }

    @Test
    void buildFilesBodyProducesCloudflarePurgeJson() throws Exception {
        String body = CfPurge.buildFilesBody(List.of("https://example.jp/congestion"));
        assertEquals("{\"files\":[\"https://example.jp/congestion\"]}", body);
    }
}
