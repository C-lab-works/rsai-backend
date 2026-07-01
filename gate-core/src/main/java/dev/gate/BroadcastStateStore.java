package dev.gate;

import dev.gate.core.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * broadcast_state（単一行）を扱う静的ヘルパー。
 * Firestore の broadcast/cache ドキュメントを置き換える。
 * キーは呼び出し側の camelCase（refreshAt 等）で受け渡しし、内部で snake_case カラムに変換する。
 */
public class BroadcastStateStore {

    private static final Map<String, String> COLUMN_BY_KEY = Map.of(
        "refreshAt",       "refresh_at",
        "congestionAt",    "congestion_at",
        "starsEnabledAt",  "stars_enabled_at",
        "starsEnabled",    "stars_enabled"
    );

    private BroadcastStateStore() {}

    public static Map<String, Object> read() throws SQLException {
        Map<String, Object> result = new HashMap<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT refresh_at, congestion_at, stars_enabled_at, stars_enabled " +
                 "FROM broadcast_state WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return result;
            result.put("refreshAt",    rs.getString("refresh_at"));
            result.put("congestionAt", rs.getString("congestion_at"));
            result.put("starsEnabledAt", rs.getString("stars_enabled_at"));
            // tinyInt1isBit=false のため getBoolean で明示変換する
            boolean starsEnabled = rs.getBoolean("stars_enabled");
            result.put("starsEnabled", rs.wasNull() ? null : starsEnabled);
        }
        return result;
    }

    /** fields に含まれるキーのみ更新する（未指定フィールドは上書きしない）。 */
    public static void upsert(Map<String, Object> fields) throws SQLException {
        if (fields == null || fields.isEmpty()) return;

        Map<String, Object> columns = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            String column = COLUMN_BY_KEY.get(e.getKey());
            if (column == null) throw new IllegalArgumentException("Unknown broadcast_state key: " + e.getKey());
            columns.put(column, e.getValue());
        }

        StringBuilder colNames  = new StringBuilder();
        StringBuilder placeholders = new StringBuilder();
        StringBuilder updateClause = new StringBuilder();
        for (String col : columns.keySet()) {
            if (colNames.length() > 0) { colNames.append(", "); placeholders.append(", "); updateClause.append(", "); }
            colNames.append(col);
            placeholders.append("?");
            updateClause.append(col).append(" = VALUES(").append(col).append(")");
        }

        String sql = "INSERT INTO broadcast_state (id, " + colNames + ") VALUES (1, " + placeholders + ") " +
                     "ON DUPLICATE KEY UPDATE " + updateClause;

        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            for (Object v : columns.values()) {
                if (v instanceof Boolean b) ps.setBoolean(i++, b);
                else ps.setString(i++, (String) v);
            }
            ps.executeUpdate();
        }
    }
}
