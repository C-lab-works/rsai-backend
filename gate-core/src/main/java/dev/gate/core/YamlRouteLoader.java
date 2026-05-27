package dev.gate.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * routes.yaml に定義されたエントリを Gate フレームワークに GET ルートとして登録する。
 *
 * YAML 形式:
 * <pre>
 * routes:
 *   - path: /api/locations
 *     table: locations
 *     columns: [id, name, floor, location_code]
 * </pre>
 *
 * 各エントリは "SELECT {columns} FROM {table}" を自動生成し、ResultSet を JSON 配列で返す。
 * WHERE 句・JOIN・ORDER BY が必要な場合は Java コントローラーで実装すること。
 */
public class YamlRouteLoader {

    private static final Logger log = new Logger(YamlRouteLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // テーブル名・カラム名に英数字とアンダースコアのみ許可（SQLインジェクション防止）
    private static final Pattern SAFE_IDENT = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    @SuppressWarnings("unchecked")
    public static void load(Gate gate) {
        try (InputStream is = YamlRouteLoader.class.getClassLoader()
                .getResourceAsStream("routes.yaml")) {
            if (is == null) {
                log.info("routes.yaml not found — YAML routes skipped");
                return;
            }

            Yaml yaml = new Yaml();
            Map<String, Object> config = yaml.load(is);
            if (config == null) return;

            List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
            if (routes == null || routes.isEmpty()) {
                log.info("routes.yaml: no routes defined");
                return;
            }

            int registered = 0;
            for (Map<String, Object> entry : routes) {
                String path          = (String)       entry.get("path");
                String table         = (String)       entry.get("table");
                List<String> columns = (List<String>) entry.get("columns");

                if (path == null || table == null || columns == null || columns.isEmpty()) {
                    log.warn("YAML route skipped — missing path/table/columns: {}", entry);
                    continue;
                }
                if (!SAFE_IDENT.matcher(table).matches()) {
                    log.warn("YAML route '{}' skipped — unsafe table name: {}", path, table);
                    continue;
                }
                boolean safe = true;
                for (String col : columns) {
                    if (!SAFE_IDENT.matcher(col).matches()) {
                        log.warn("YAML route '{}' skipped — unsafe column name: {}", path, col);
                        safe = false;
                        break;
                    }
                }
                if (!safe) continue;

                String sql = "SELECT " + String.join(", ", columns) + " FROM " + table;

                gate.get(path, ctx -> {
                    try (Connection conn = Database.getConnection();
                         Statement  stmt = conn.createStatement();
                         ResultSet  rs   = stmt.executeQuery(sql)) {

                        ArrayNode arr = MAPPER.createArrayNode();
                        ResultSetMetaData meta = rs.getMetaData();
                        int colCount = meta.getColumnCount();

                        while (rs.next()) {
                            ObjectNode node = arr.addObject();
                            for (int i = 1; i <= colCount; i++) {
                                putColumn(node, meta.getColumnLabel(i), meta.getColumnType(i), rs, i);
                            }
                        }
                        ctx.jsonBytes(MAPPER.writeValueAsBytes(arr));
                    } catch (Exception e) {
                        log.error("YAML route error GET {}: {}", path, e.getMessage(), e);
                        ctx.status(503).json(Map.of("error", "database error"));
                    }
                });

                log.info("YAML route: GET {} → {}", path, sql);
                registered++;
            }
            log.info("YAML routes: {} registered", registered);

        } catch (Exception e) {
            log.warn("Failed to load routes.yaml: {}", e.getMessage(), e);
        }
    }

    private static void putColumn(ObjectNode node, String name, int sqlType, ResultSet rs, int idx)
            throws Exception {
        switch (sqlType) {
            case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> {
                int v = rs.getInt(idx);
                if (rs.wasNull()) node.putNull(name); else node.put(name, v);
            }
            case Types.BIGINT -> {
                long v = rs.getLong(idx);
                if (rs.wasNull()) node.putNull(name); else node.put(name, v);
            }
            case Types.FLOAT, Types.REAL -> {
                float v = rs.getFloat(idx);
                if (rs.wasNull()) node.putNull(name); else node.put(name, v);
            }
            case Types.DOUBLE -> {
                double v = rs.getDouble(idx);
                if (rs.wasNull()) node.putNull(name); else node.put(name, v);
            }
            case Types.BOOLEAN, Types.BIT -> {
                boolean v = rs.getBoolean(idx);
                if (rs.wasNull()) node.putNull(name); else node.put(name, v);
            }
            default -> {
                String v = rs.getString(idx);
                if (v == null) node.putNull(name); else node.put(name, v);
            }
        }
    }
}
