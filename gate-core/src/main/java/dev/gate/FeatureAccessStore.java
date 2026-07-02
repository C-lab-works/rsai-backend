package dev.gate;

import dev.gate.core.Database;
import dev.gate.core.Logger;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 機能別メールACL(混雑度/遅延など)の許可判定。controller ではない小さな static ヘルパー
 * （Main.java 登録や reflect-config 追加は不要）。
 * feature_access テーブルを全行ロードして TTL 30秒でキャッシュする
 * （CfAccessAuth.adminEmailsRef のキャッシュ流儀に倣う）。
 */
public class FeatureAccessStore {
    private static final Logger logger = new Logger(FeatureAccessStore.class);
    private static final long CACHE_TTL_MILLIS = 30_000L;
    // X-Service-Key 経由の M2M アクセスが名乗る既存の内部アイデンティティ（既存挙動を維持）。
    private static final String SERVICE_IDENTITY = "service@internal";

    // null = 未ロード（fail-closed 対象）。ロード成功後は空集合もあり得る（正当に0件の場合）。
    private static volatile Set<String> cachedKeys = null;
    private static volatile long cachedAtMillis = 0L;

    private static final Set<String> ownerEmailsRef = parseOwnerEmails();

    private FeatureAccessStore() {}

    // ── 許可リスト判定 ───────────────────────────────────────────

    public static boolean isAllowed(String feature, String email) {
        if (feature == null || feature.isBlank() || email == null || email.isBlank()) return false;
        Set<String> keys = snapshot();
        return keys.contains(key(feature, email));
    }

    private static Set<String> snapshot() {
        long now = System.currentTimeMillis();
        Set<String> current = cachedKeys;
        if (current != null && now - cachedAtMillis < CACHE_TTL_MILLIS) {
            return current;
        }
        synchronized (FeatureAccessStore.class) {
            now = System.currentTimeMillis();
            current = cachedKeys;
            if (current != null && now - cachedAtMillis < CACHE_TTL_MILLIS) {
                return current;
            }
            try {
                Set<String> fresh = loadFromDb();
                cachedKeys = fresh;
                cachedAtMillis = now;
                return fresh;
            } catch (SQLException e) {
                logger.warn("FeatureAccessStore: failed to load feature_access ({}); serving {}",
                        e.getMessage(), cachedKeys != null ? "stale cache" : "empty (fail-closed)");
                return cachedKeys != null ? cachedKeys : Set.of();
            }
        }
    }

    private static Set<String> loadFromDb() throws SQLException {
        Set<String> keys = new HashSet<>();
        try (Connection conn = Database.getConnection();
             Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT feature, email FROM feature_access")) {
            while (rs.next()) {
                keys.add(key(rs.getString("feature"), rs.getString("email")));
            }
        }
        return Set.copyOf(keys);
    }

    private static String key(String feature, String email) {
        return feature + "\n" + email.toLowerCase();
    }

    // ── owner / privileged 判定 ──────────────────────────────────

    /** owner または admin、あるいは X-Service-Key 経由の内部アイデンティティなら常に許可。 */
    public static boolean isPrivileged(String email) {
        if (email == null || email.isBlank()) return false;
        if (SERVICE_IDENTITY.equals(email)) return true;
        if (CfAccessAuth.isAdmin(email)) return true;
        return ownerEmailsRef.contains(email.toLowerCase());
    }

    private static Set<String> parseOwnerEmails() {
        String owners = System.getenv("OWNER_EMAILS");
        if (owners == null || owners.isBlank()) return Set.of();
        return Arrays.stream(owners.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
