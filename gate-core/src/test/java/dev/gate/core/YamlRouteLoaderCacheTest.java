package dev.gate.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link YamlRouteLoader#parseCacheSeconds} の回帰テスト。 */
class YamlRouteLoaderCacheTest {

    @Test
    void parsesSeconds() {
        assertEquals(10, YamlRouteLoader.parseCacheSeconds("10s"));
        assertEquals(0, YamlRouteLoader.parseCacheSeconds("0s"));
    }

    @Test
    void parsesMinutesAndHours() {
        assertEquals(300, YamlRouteLoader.parseCacheSeconds("5m"));
        assertEquals(3600, YamlRouteLoader.parseCacheSeconds("1h"));
    }

    @Test
    void zeroAnyUnitIsDisabled() {
        assertEquals(0, YamlRouteLoader.parseCacheSeconds("0m"));
        assertEquals(0, YamlRouteLoader.parseCacheSeconds("0h"));
    }

    @Test
    void caseAndWhitespaceInsensitive() {
        assertEquals(30, YamlRouteLoader.parseCacheSeconds(" 30S "));
        assertEquals(120, YamlRouteLoader.parseCacheSeconds("2 M"));
    }

    @Test
    void nullAndInvalidDisableCache() {
        assertEquals(0, YamlRouteLoader.parseCacheSeconds(null));
        assertEquals(0, YamlRouteLoader.parseCacheSeconds(""));
        assertEquals(0, YamlRouteLoader.parseCacheSeconds("abc"));
        assertEquals(0, YamlRouteLoader.parseCacheSeconds("10"));      // 単位なし
        assertEquals(0, YamlRouteLoader.parseCacheSeconds("10d"));     // 未対応単位
        assertEquals(0, YamlRouteLoader.parseCacheSeconds("-5s"));     // 負値（パターン不一致）
    }

    @Test
    void numericValueIsTreatedAsInvalid() {
        // YAML が cache: 30 と数値で解釈した場合は単位なし → 無効（0）
        assertEquals(0, YamlRouteLoader.parseCacheSeconds(Integer.valueOf(30)));
    }
}
