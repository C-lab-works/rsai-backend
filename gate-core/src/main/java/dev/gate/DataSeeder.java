package dev.gate;

import dev.gate.core.Database;
import dev.gate.core.Logger;

import java.sql.*;

public class DataSeeder {
    private static final Logger logger = new Logger(DataSeeder.class);

    public static void seed() throws Exception {
        try (Connection conn = Database.getConnection()) {
            int v = getSeedVersion(conn);
            if (v >= 3) {
                logger.info("Seed data v3 already present — skipping");
                return;
            }
            if (v == 1) {
                logger.info("Migrating schema v1 -> v3");
                migrateV1(conn);
            }
            if (v <= 1) {
                createTables(conn);
                seedCategories(conn);
                seedLocations(conn);
                seedProjects(conn);
                seedTimetables(conn);
                seedAnnouncements(conn);
            }
            if (v == 2) {
                logger.info("Migrating schema v2 -> v3");
                migrateV2(conn);
            }
            setSeedVersion(conn, 3);
            logger.info("Seed data v3 ready");
        }
    }

    // ── version ───────────────────────────────────────────────

    private static int getSeedVersion(Connection conn) throws Exception {
        exec(conn, "INSERT IGNORE INTO seed_version (id, version) VALUES (1, 0)");
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT version FROM seed_version WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt("version") : 0;
        }
    }

    private static void setSeedVersion(Connection conn, int v) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE seed_version SET version = ? WHERE id = 1")) {
            ps.setInt(1, v);
            ps.executeUpdate();
        }
    }

    // ── migrations ────────────────────────────────────────────

    private static void migrateV1(Connection conn) throws Exception {
        exec(conn, "TRUNCATE TABLE congestion_status");
        for (String t : new String[]{
                "timetables", "projects", "events", "vendor_hours", "rooms",
                "days", "event_venues", "event_categories", "festival",
                "vendors", "dining_areas", "food_rules", "eco_stations",
                "floors", "outdoor_areas", "poi", "map_config", "map_notes",
                "categories", "locations", "announcements"}) {
            exec(conn, "DROP TABLE IF EXISTS " + t);
        }
    }

    private static void migrateV2(Connection conn) throws Exception {
        try {
            exec(conn, "ALTER TABLE locations ADD COLUMN tracks_congestion TINYINT(1) NOT NULL DEFAULT 1");
            logger.info("Added tracks_congestion column to locations");
        } catch (Exception ignored) {
            // column already exists
        }
    }

    // ── DDL ───────────────────────────────────────────────────

    private static void createTables(Connection conn) throws Exception {
        exec(conn,
            "CREATE TABLE IF NOT EXISTS categories (" +
            "  id   INT          PRIMARY KEY AUTO_INCREMENT," +
            "  name VARCHAR(255) NOT NULL" +
            ")");
        exec(conn,
            "CREATE TABLE IF NOT EXISTS locations (" +
            "  id                INT          PRIMARY KEY AUTO_INCREMENT," +
            "  name              VARCHAR(255) NOT NULL," +
            "  floor             INT          NOT NULL DEFAULT 0," +
            "  svg_id            VARCHAR(255)," +
            "  tracks_congestion TINYINT(1)   NOT NULL DEFAULT 1" +
            ")");
        exec(conn,
            "CREATE TABLE IF NOT EXISTS projects (" +
            "  id          INT          PRIMARY KEY AUTO_INCREMENT," +
            "  category_id INT," +
            "  title       VARCHAR(255) NOT NULL," +
            "  organizer   VARCHAR(255)," +
            "  description TEXT," +
            "  image_url   VARCHAR(255)," +
            "  FOREIGN KEY (category_id) REFERENCES categories(id)" +
            ")");
        exec(conn,
            "CREATE TABLE IF NOT EXISTS timetables (" +
            "  id          INT        PRIMARY KEY AUTO_INCREMENT," +
            "  project_id  INT        NOT NULL," +
            "  location_id INT        NOT NULL," +
            "  event_date  DATE       NOT NULL," +
            "  is_all_day  TINYINT(1) NOT NULL DEFAULT 0," +
            "  start_time  TIME," +
            "  end_time    TIME," +
            "  FOREIGN KEY (project_id)  REFERENCES projects(id)," +
            "  FOREIGN KEY (location_id) REFERENCES locations(id)" +
            ")");
        exec(conn,
            "CREATE TABLE IF NOT EXISTS announcements (" +
            "  id            INT        PRIMARY KEY AUTO_INCREMENT," +
            "  content       TEXT       NOT NULL," +
            "  is_emergency  TINYINT(1) NOT NULL DEFAULT 0," +
            "  display_from  DATETIME," +
            "  display_until DATETIME" +
            ")");
    }

    // ── seed data ─────────────────────────────────────────────

    private static void seedCategories(Connection conn) throws Exception {
        exec(conn,
            "INSERT IGNORE INTO categories (id, name) VALUES " +
            "(1, 'ステージ企画'), " +
            "(2, 'クラス企画'), " +
            "(3, '演劇'), " +
            "(4, '展示・体験'), " +
            "(5, '飲食')");
    }

    private static void seedLocations(Connection conn) throws Exception {
        exec(conn,
            "INSERT IGNORE INTO locations (id, name, floor, svg_id, tracks_congestion) VALUES " +
            "(1,  '体育館',              1, 'gym',     1), " +
            "(2,  'メインステージ',       1, 'stage',   1), " +
            "(3,  '3-A教室',             2, 'room-3a', 1), " +
            "(4,  '3-B教室',             2, 'room-3b', 1), " +
            "(5,  '3-C教室',             2, 'room-3c', 1), " +
            "(6,  '4-A教室',             3, 'room-4a', 1), " +
            "(7,  '4-B教室',             3, 'room-4b', 1), " +
            "(8,  '中庭',                0, 'yard',    1), " +
            "(9,  'キッチンカーエリア',   0, 'kitchen', 1), " +
            "(10, '正門前広場',           0, 'gate',    1)");
    }

    private static void seedProjects(Connection conn) throws Exception {
        exec(conn,
            "INSERT IGNORE INTO projects (id, category_id, title, organizer) VALUES " +
            "(1, 1, 'ステージ企画（タイトル未定）', '実行委員会'), " +
            "(2, 2, '3-Aクラス企画（未定）',       '3年A組'),     " +
            "(3, 3, '演劇（タイトル未定）',         '演劇部'),     " +
            "(4, 4, '展示企画（未定）',             '4年A組'),     " +
            "(5, 5, '飲食企画（未定）',             '模擬店委員会')");
    }

    private static void seedTimetables(Connection conn) throws Exception {
        exec(conn,
            "INSERT IGNORE INTO timetables (id, project_id, location_id, event_date, is_all_day, start_time, end_time) VALUES " +
            "(1, 1, 2, '2026-07-04', 0, '10:00:00', '11:00:00'), " +
            "(2, 2, 3, '2026-07-04', 1, NULL, NULL),             " +
            "(3, 3, 1, '2026-07-05', 0, '13:00:00', '14:00:00'), " +
            "(4, 4, 6, '2026-07-04', 1, NULL, NULL),             " +
            "(5, 5, 9, '2026-07-04', 0, '10:00:00', '15:30:00')");
    }

    private static void seedAnnouncements(Connection conn) throws Exception {
        exec(conn,
            "INSERT IGNORE INTO announcements (id, content, is_emergency) VALUES " +
            "(1, 'ここにお知らせを表示できます（テスト表示）', 0), " +
            "(2, '【緊急】ここに緊急お知らせを表示できます（テスト表示）', 1)");
    }

    // ── util ──────────────────────────────────────────────────

    private static void exec(Connection conn, String sql) throws Exception {
        try (Statement s = conn.createStatement()) {
            s.execute(sql);
        }
    }
}
