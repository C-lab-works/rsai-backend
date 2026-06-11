package dev.gate.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HttpCache.etagMatches の単体テスト（RFC 9110 §13.1.2）。
 */
public class HttpCacheEtagMatchTest {

    private static final String ETAG = "\"abc123\"";

    @Test
    void exactMatchReturnsTrue() {
        assertTrue(HttpCache.etagMatches(ETAG, ETAG));
    }

    @Test
    void weakEtagFormMatchesStrongEtag() {
        // W/"abc123" は "abc123" に弱い比較でマッチする
        assertTrue(HttpCache.etagMatches("W/" + ETAG, ETAG));
    }

    @Test
    void wildcardMatchesAnyEtag() {
        assertTrue(HttpCache.etagMatches("*", ETAG));
        assertTrue(HttpCache.etagMatches("*", "\"anything\""));
    }

    @Test
    void commaSeparatedListMatchesWhenOneEntryMatches() {
        assertTrue(HttpCache.etagMatches("\"other\", " + ETAG + ", \"another\"", ETAG));
    }

    @Test
    void commaSeparatedListWithWeakFormMatches() {
        assertTrue(HttpCache.etagMatches("\"other\", W/" + ETAG, ETAG));
    }

    @Test
    void nonMatchingValueReturnsFalse() {
        assertFalse(HttpCache.etagMatches("\"deadbeef\"", ETAG));
    }

    @Test
    void nullIfNoneMatchReturnsFalse() {
        assertFalse(HttpCache.etagMatches(null, ETAG));
    }

    @Test
    void emptyListReturnsFalse() {
        assertFalse(HttpCache.etagMatches("", ETAG));
    }
}
