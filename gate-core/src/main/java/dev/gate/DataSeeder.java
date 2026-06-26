package dev.gate;

import dev.gate.core.Database;
import dev.gate.core.Logger;

import java.sql.*;

public class DataSeeder {
    private static final Logger logger = new Logger(DataSeeder.class);

    public static void seed() throws Exception {
        try (Connection conn = Database.getConnection()) {
            int v = getSeedVersion(conn);
            if (!tableExists(conn, "locations") || !tableExists(conn, "categories") || !tableExists(conn, "projects")) {
                logger.warn("Core tables missing (locations/categories/projects) — resetting seed version to 0");
                v = 0;
            }
            if (v >= 29) {
                logger.info("Seed data v29 already present — skipping");
                return;
            }
            if (v == 1) {
                logger.info("Migrating schema v1 -> v5");
                migrateV1(conn);
            }
            if (v <= 1) {
                defineTables(conn);
                seedCategories(conn);
                seedLocations(conn);
                seedProjects(conn);
                seedTimetables(conn);
                seedAnnouncements(conn);
                seedProjectCategories(conn);
                setSeedVersion(conn, 2);
            }
            if (v == 2) {
                logger.info("Migrating schema v2 -> v3");
                migrateV2(conn);
                setSeedVersion(conn, 3);
            }
            if (v <= 3) {
                logger.info("Migrating schema v3 -> v4");
                migrateV3(conn);
                setSeedVersion(conn, 4);
            }
            if (v <= 4) {
                logger.info("Migrating schema v4 -> v5");
                migrateV4(conn);
                setSeedVersion(conn, 5);
            }
            if (v <= 5) {
                logger.info("Migrating schema v5 -> v6");
                migrateV5(conn);
                setSeedVersion(conn, 6);
            }
            if (v <= 6) {
                logger.info("Migrating schema v6 -> v7");
                migrateV6(conn);
                setSeedVersion(conn, 7);
            }
            if (v <= 7) {
                logger.info("Migrating schema v7 -> v8");
                migrateV7(conn);
                setSeedVersion(conn, 8);
            }
            if (v <= 8) {
                logger.info("Migrating schema v8 -> v9");
                migrateV8(conn);
                setSeedVersion(conn, 9);
            }
            if (v <= 9) {
                logger.info("Migrating schema v9 -> v10");
                migrateV9(conn);
                setSeedVersion(conn, 10);
            }
            if (v <= 10) {
                logger.info("Migrating schema v10 -> v11");
                migrateV10(conn);
                setSeedVersion(conn, 11);
            }
            if (v <= 11) {
                logger.info("Migrating schema v11 -> v12");
                migrateV11(conn);
                setSeedVersion(conn, 12);
            }
            if (v <= 12) {
                logger.info("Migrating schema v12 -> v13");
                migrateV12(conn);
                setSeedVersion(conn, 13);
            }
            if (v <= 13) {
                logger.info("Migrating schema v13 -> v14");
                migrateV13(conn);
                setSeedVersion(conn, 14);
            }
            if (v <= 14) {
                logger.info("Migrating schema v14 -> v15");
                migrateV14(conn);
                setSeedVersion(conn, 15);
            }
            if (v <= 15) {
                logger.info("Migrating schema v15 -> v16");
                migrateV15(conn);
                setSeedVersion(conn, 16);
            }
            if (v <= 16) {
                logger.info("Migrating schema v16 -> v17");
                migrateV16(conn);
                setSeedVersion(conn, 17);
            }
            if (v <= 17) {
                logger.info("Migrating schema v17 -> v18");
                migrateV17(conn);
                setSeedVersion(conn, 18);
            }
            if (v <= 18) {
                logger.info("Migrating schema v18 -> v19");
                migrateV18(conn);
                setSeedVersion(conn, 19);
            }
            if (v <= 19) {
                logger.info("Migrating schema v19 -> v20");
                migrateV19(conn);
                setSeedVersion(conn, 20);
            }
            if (v <= 20) {
                logger.info("Migrating schema v20 -> v21");
                migrateV20(conn);
                setSeedVersion(conn, 21);
            }
            if (v <= 21) {
                logger.info("Migrating schema v21 -> v22");
                migrateV21(conn);
                setSeedVersion(conn, 22);
            }
            if (v <= 22) {
                logger.info("Migrating schema v22 -> v23");
                migrateV22(conn);
                setSeedVersion(conn, 23);
            }
            if (v <= 23) {
                logger.info("Migrating schema v23 -> v24");
                migrateV23(conn);
                setSeedVersion(conn, 24);
            }
            if (v <= 24) {
                logger.info("Migrating schema v24 -> v25");
                migrateV24(conn);
                setSeedVersion(conn, 25);
            }
            if (v <= 25) {
                logger.info("Migrating schema v25 -> v26");
                migrateV25(conn);
                setSeedVersion(conn, 26);
            }
            if (v <= 26) {
                logger.info("Migrating schema v26 -> v27");
                migrateV26(conn);
                setSeedVersion(conn, 27);
            }
            if (v <= 27) {
                logger.info("Migrating schema v27 -> v28");
                migrateV27(conn);
                setSeedVersion(conn, 28);
            }
            if (v <= 28) {
                logger.info("Migrating schema v28 -> v29");
                migrateV28(conn);
                setSeedVersion(conn, 29);
            }
            logger.info("Seed data v29 ready");
        }
    }

    // ── version ───────────────────────────────────────────────

    private static int getSeedVersion(Connection conn) throws Exception {
        exec(conn, "CREATE TABLE IF NOT EXISTS seed_version (id INT PRIMARY KEY, version INT NOT NULL DEFAULT 0)");
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
            exec(conn, "DROP TABLE IF EXISTS `" + t + "`");
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

    private static void migrateV3(Connection conn) throws Exception {
        try {
            exec(conn, "ALTER TABLE locations ADD COLUMN svg_id VARCHAR(255)");
            logger.info("Added svg_id column to locations");
        } catch (Exception ignored) {
            // column already exists
        }
    }

    private static void migrateV4(Connection conn) throws Exception {
        try {
            exec(conn, "ALTER TABLE locations ADD COLUMN is_stage TINYINT(1) NOT NULL DEFAULT 1");
            logger.info("Added is_stage column to locations");
        } catch (Exception ignored) {
            // column already exists
        }
    }

    private static void migrateV5(Connection conn) throws Exception {
        try {
            exec(conn, "ALTER TABLE projects DROP COLUMN category_id");
            logger.info("Dropped category_id from projects");
        } catch (Exception ignored) {
            // column already removed
        }
        try {
            exec(conn, "ALTER TABLE projects ADD COLUMN location_id INT");
            logger.info("Added location_id to projects");
        } catch (Exception ignored) {
            // column already exists
        }
        defineTables(conn);
        logger.info("Created project_categories and related tables");
    }

    private static void migrateV6(Connection conn) throws Exception {
        try {
            exec(conn, "ALTER TABLE congestion_status MODIFY COLUMN level TINYINT(4) NOT NULL DEFAULT 0");
            logger.info("Fixed congestion_status.level type (TINYINT -> TINYINT(4))");
        } catch (Exception ignored) {}
    }

    private static void migrateV7(Connection conn) throws Exception {
        try {
            exec(conn, "ALTER TABLE announcements ADD COLUMN title VARCHAR(255) NOT NULL DEFAULT '' AFTER id");
            logger.info("Added title column to announcements");
        } catch (Exception ignored) {
            // column already exists
        }
    }

    private static void migrateV8(Connection conn) throws Exception {
        try {
            exec(conn, "ALTER TABLE announcements MODIFY COLUMN title VARCHAR(255) NOT NULL DEFAULT ''");
            logger.info("Fixed announcements.title to VARCHAR(255) NOT NULL DEFAULT ''");
        } catch (Exception ignored) {}
    }

    private static void migrateV9(Connection conn) throws Exception {
        try {
            exec(conn, "ALTER TABLE locations ADD COLUMN x DOUBLE");
            logger.info("Added x column to locations");
        } catch (Exception ignored) {}
        try {
            exec(conn, "ALTER TABLE locations ADD COLUMN y DOUBLE");
            logger.info("Added y column to locations");
        } catch (Exception ignored) {}
    }

    private static void migrateV10(Connection conn) throws Exception {
        addColumnIfMissing(conn, "locations", "x", "DOUBLE");
        addColumnIfMissing(conn, "locations", "y", "DOUBLE");
        logger.info("Ensured x, y columns on locations");
    }

    private static void addColumnIfMissing(Connection conn, String table, String column, String type) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    exec(conn, "ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + type);
                    logger.info("Added {} column to {}", column, table);
                }
            }
        }
    }

    // ── DDL ───────────────────────────────────────────────────

    /** Canonical latest schema. Idempotent — safe to call on any existing database. */
    private static void defineTables(Connection conn) throws Exception {
        exec(conn,
            "CREATE TABLE IF NOT EXISTS categories (" +
            "  id   INT          PRIMARY KEY AUTO_INCREMENT," +
            "  name VARCHAR(255) NOT NULL" +
            ")");
        exec(conn,
            "CREATE TABLE IF NOT EXISTS locations (" +
            "  id            INT          PRIMARY KEY AUTO_INCREMENT," +
            "  name          VARCHAR(255) NOT NULL," +
            "  floor         INT          NOT NULL DEFAULT 0," +
            "  location_code VARCHAR(50)  NOT NULL," +
            "  svg_id        INT," +
            "  x             DOUBLE," +
            "  y             DOUBLE," +
            "  UNIQUE INDEX ux_locations_code (location_code)" +
            ")");
        exec(conn,
            "CREATE TABLE IF NOT EXISTS congestion_status (" +
            "  location_code VARCHAR(50)  NOT NULL," +
            "  level         TINYINT(4)   NOT NULL DEFAULT 0," +
            "  updated_at    DATETIME     NOT NULL," +
            "  updated_by    VARCHAR(100) NOT NULL," +
            "  PRIMARY KEY (location_code)" +
            ")");
        exec(conn,
            "CREATE TABLE IF NOT EXISTS projects (" +
            "  id             INT          PRIMARY KEY AUTO_INCREMENT," +
            "  title          VARCHAR(255) COLLATE utf8mb4_unicode_ci NOT NULL," +
            "  organizer      VARCHAR(255) COLLATE utf8mb4_unicode_ci," +
            "  description    TEXT COLLATE utf8mb4_unicode_ci," +
            "  image_url      VARCHAR(255) COLLATE utf8mb4_unicode_ci," +
            "  location_id    INT," +
            "  bookmark_count INT          NOT NULL DEFAULT 0," +
            "  FOREIGN KEY (location_id) REFERENCES locations(id)" +
            ") COLLATE utf8mb4_unicode_ci");
        exec(conn,
            "CREATE TABLE IF NOT EXISTS project_categories (" +
            "  project_id  INT NOT NULL," +
            "  category_id INT NOT NULL," +
            "  PRIMARY KEY (project_id, category_id)," +
            "  FOREIGN KEY (project_id)  REFERENCES projects(id)," +
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
            "  id            INT          PRIMARY KEY AUTO_INCREMENT," +
            "  title         VARCHAR(255) NOT NULL DEFAULT ''," +
            "  content       TEXT         NOT NULL," +
            "  is_emergency  TINYINT(1)   NOT NULL DEFAULT 0" +
            ")");
        exec(conn,
            "CREATE TABLE IF NOT EXISTS bus (" +
            "  id                   INT          PRIMARY KEY AUTO_INCREMENT," +
            "  bus_id               INT          NOT NULL," +
            "  Destination          VARCHAR(100) NOT NULL," +
            "  School               TIME         NOT NULL," +
            "  School_Platform      VARCHAR(100)," +
            "  Shinsapporo          TIME," +
            "  Shinsapporo_Platform VARCHAR(100)," +
            "  Ooyati               TIME," +
            "  Ooasa                TIME," +
            "  Atubetu              TIME" +
            ")");
        exec(conn,
            "CREATE TABLE IF NOT EXISTS foodtruck (" +
            "  id             INT          PRIMARY KEY AUTO_INCREMENT," +
            "  name           VARCHAR(255) COLLATE utf8mb4_unicode_ci NOT NULL," +
            "  info           TEXT COLLATE utf8mb4_unicode_ci NOT NULL," +
            "  icon           VARCHAR(255) COLLATE utf8mb4_unicode_ci NOT NULL," +
            "  location_code  VARCHAR(50) COLLATE utf8mb4_unicode_ci," +
            "  bookmark_count INT          NOT NULL DEFAULT 0" +
            ") COLLATE utf8mb4_unicode_ci");
        exec(conn,
            "CREATE TABLE IF NOT EXISTS menus (" +
            "  id           INT          PRIMARY KEY AUTO_INCREMENT," +
            "  foodtruck_id INT          NOT NULL," +
            "  name         VARCHAR(255) NOT NULL," +
            "  price        INT          NOT NULL," +
            "  imageURL     VARCHAR(255)," +
            "  allergen     VARCHAR(255)," +
            "  FOREIGN KEY (foodtruck_id) REFERENCES foodtruck(id)" +
            ")");
        exec(conn,
            "CREATE TABLE IF NOT EXISTS foodtruck_sns (" +
            "  id           INT          PRIMARY KEY AUTO_INCREMENT," +
            "  foodtruck_id INT          NOT NULL," +
            "  platform     VARCHAR(50)," +
            "  url          VARCHAR(255) NOT NULL," +
            "  FOREIGN KEY (foodtruck_id) REFERENCES foodtruck(id)" +
            ")");
        exec(conn,
            "CREATE TABLE IF NOT EXISTS foodtruck_subicon (" +
            "  id           INT          PRIMARY KEY AUTO_INCREMENT," +
            "  foodtruck_id INT          NOT NULL," +
            "  url          VARCHAR(255) NOT NULL," +
            "  FOREIGN KEY (foodtruck_id) REFERENCES foodtruck(id)" +
            ")");
        exec(conn,
            "CREATE TABLE IF NOT EXISTS project_stars (" +
            "  id         BIGINT   AUTO_INCREMENT PRIMARY KEY," +
            "  project_id INT      NOT NULL," +
            "  created_at DATETIME NOT NULL," +
            "  FOREIGN KEY (project_id) REFERENCES projects(id)" +
            ")");
        exec(conn,
            "CREATE TABLE IF NOT EXISTS foodtruck_stars (" +
            "  id           BIGINT   AUTO_INCREMENT PRIMARY KEY," +
            "  foodtruck_id INT      NOT NULL," +
            "  created_at   DATETIME NOT NULL," +
            "  FOREIGN KEY (foodtruck_id) REFERENCES foodtruck(id)" +
            ")");
    }

    // ── seed data ─────────────────────────────────────────────

    private static void seedCategories(Connection conn) throws Exception {
        exec(conn,
            "INSERT IGNORE INTO categories (id, name) VALUES " +
            "(1, 'ステージ系'), " +
            "(2, 'クラス企画'), " +
            "(3, '部活'), " +
            "(4, '展示'), " +
            "(5, 'フード')");
    }

    private static void seedLocations(Connection conn) throws Exception {
        exec(conn,
            "INSERT IGNORE INTO locations (id, name, floor, location_code) VALUES " +
            "(1,  '体育館',              1, 'gym'), " +
            "(2,  'メインステージ',       1, 'stage'), " +
            "(3,  '3-A教室',             2, 'room-3a'), " +
            "(4,  '3-B教室',             2, 'room-3b'), " +
            "(5,  '3-C教室',             2, 'room-3c'), " +
            "(6,  '4-A教室',             3, 'room-4a'), " +
            "(7,  '4-B教室',             3, 'room-4b'), " +
            "(8,  '中庭',                0, 'yard'), " +
            "(9,  'キッチンカーエリア',   0, 'kitchen'), " +
            "(10, '正門前広場',           0, 'gate')");
    }

    private static void seedProjects(Connection conn) throws Exception {
        exec(conn,
            "INSERT IGNORE INTO projects (id, title, organizer, location_id) VALUES " +
            "(1, 'ステージ企画（タイトル未定）', '実行委員会',   2), " +
            "(2, '3-Aクラス企画（未定）',       '3年A組',       3), " +
            "(3, '演劇（タイトル未定）',         '演劇部',       1), " +
            "(4, '展示企画（未定）',             '4年A組',       6), " +
            "(5, '飲食企画（未定）',             '模擬店委員会', 9)");
    }

    private static void seedProjectCategories(Connection conn) throws Exception {
        exec(conn,
            "INSERT IGNORE INTO project_categories (project_id, category_id) VALUES " +
            "(1, 1), " +
            "(2, 2), " +
            "(3, 3), " +
            "(4, 4), " +
            "(5, 5)");
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

    private static void migrateV11(Connection conn) throws Exception {
        // Fix any blank/invalid location_code values before making NOT NULL
        exec(conn,
            "UPDATE locations SET location_code = CONCAT('loc-', id) " +
            "WHERE location_code IS NULL OR TRIM(location_code) = '' OR location_code = '0'");

        try {
            exec(conn, "ALTER TABLE locations MODIFY COLUMN location_code VARCHAR(50) NOT NULL");
            exec(conn, "ALTER TABLE locations ADD UNIQUE INDEX ux_locations_code (location_code)");
            logger.info("Made location_code NOT NULL UNIQUE on locations");
        } catch (Exception e) {
            logger.warn("location_code constraint may already exist: {}", e.getMessage());
        }

        // Migrate congestion_status: location_id (INT PK) → location_code (VARCHAR PK)
        if (columnExists(conn, "congestion_status", "location_id")) {
            addColumnIfMissing(conn, "congestion_status", "location_code", "VARCHAR(50)");

            exec(conn,
                "UPDATE congestion_status cs " +
                "INNER JOIN locations l ON l.id = cs.location_id " +
                "SET cs.location_code = l.location_code " +
                "WHERE cs.location_code IS NULL");

            // Delete orphaned rows with no matching location
            exec(conn, "DELETE FROM congestion_status WHERE location_code IS NULL");

            try {
                exec(conn, "ALTER TABLE congestion_status MODIFY COLUMN location_code VARCHAR(50) NOT NULL");
                exec(conn, "ALTER TABLE congestion_status DROP PRIMARY KEY");
                exec(conn, "ALTER TABLE congestion_status ADD PRIMARY KEY (location_code)");
                logger.info("Changed congestion_status PK to location_code");
            } catch (Exception e) {
                logger.warn("congestion_status PK update issue: {}", e.getMessage());
            }
            dropColumnIfExists(conn, "congestion_status", "location_id");
        }

        dropColumnIfExists(conn, "locations", "tracks_congestion");
        dropColumnIfExists(conn, "locations", "is_stage");
        logger.info("Dropped tracks_congestion, is_stage from locations");
    }

    private static void migrateV12(Connection conn) throws Exception {
        if (!columnExists(conn, "metrics_endpoints", "date")) {
            exec(conn, "TRUNCATE TABLE metrics_endpoints");
            exec(conn, "ALTER TABLE metrics_endpoints DROP PRIMARY KEY");
            exec(conn, "ALTER TABLE metrics_endpoints ADD COLUMN date DATE NOT NULL DEFAULT '2000-01-01'");
            exec(conn, "ALTER TABLE metrics_endpoints ADD PRIMARY KEY (endpoint, date)");
            logger.info("Added date column to metrics_endpoints with composite PK (endpoint, date)");
        }
    }

    private static void migrateV13(Connection conn) throws Exception {
        exec(conn,
            "CREATE TABLE IF NOT EXISTS bus (" +
            "  id                   INT          PRIMARY KEY AUTO_INCREMENT," +
            "  bus_id               INT          NOT NULL," +
            "  Destination          VARCHAR(100) NOT NULL," +
            "  School               TIME         NOT NULL," +
            "  School_Platform      VARCHAR(100)," +
            "  Shinsapporo          TIME," +
            "  Shinsapporo_Platform VARCHAR(100)," +
            "  Ooyati               TIME," +
            "  Ooasa                TIME," +
            "  Atubetu              TIME" +
            ")");
        logger.info("Created bus table");
    }

    private static void migrateV14(Connection conn) throws Exception {
        exec(conn,
            "CREATE TABLE IF NOT EXISTS foodtruck (" +
            "  id   INT          PRIMARY KEY AUTO_INCREMENT," +
            "  name VARCHAR(255) NOT NULL," +
            "  info TEXT         NOT NULL," +
            "  icon VARCHAR(255) NOT NULL" +
            ")");
        exec(conn,
            "CREATE TABLE IF NOT EXISTS menus (" +
            "  id           INT          PRIMARY KEY AUTO_INCREMENT," +
            "  foodtruck_id INT          NOT NULL," +
            "  name         VARCHAR(255) NOT NULL," +
            "  price        INT          NOT NULL," +
            "  imageURL     VARCHAR(255)," +
            "  allergen     VARCHAR(255)," +
            "  FOREIGN KEY (foodtruck_id) REFERENCES foodtruck(id)" +
            ")");
        logger.info("Created foodtruck and menus tables");
    }

    private static void migrateV15(Connection conn) throws Exception {
        addColumnIfMissing(conn, "foodtruck", "subicon", "VARCHAR(255)");
        logger.info("Added subicon column to foodtruck");
    }

    private static void migrateV16(Connection conn) throws Exception {
        addColumnIfMissing(conn, "foodtruck", "location_code", "VARCHAR(50)");
        logger.info("Added location_code column to foodtruck");
    }

    private static void migrateV17(Connection conn) throws Exception {
        exec(conn,
            "CREATE TABLE IF NOT EXISTS foodtruck_sns (" +
            "  id           INT          PRIMARY KEY AUTO_INCREMENT," +
            "  foodtruck_id INT          NOT NULL," +
            "  platform     VARCHAR(50)," +
            "  url          VARCHAR(255) NOT NULL," +
            "  FOREIGN KEY (foodtruck_id) REFERENCES foodtruck(id)" +
            ")");
        logger.info("Created foodtruck_sns table");
    }

    private static void migrateV18(Connection conn) throws Exception {
        exec(conn,
            "CREATE TABLE IF NOT EXISTS foodtruck_subicon (" +
            "  id           INT          PRIMARY KEY AUTO_INCREMENT," +
            "  foodtruck_id INT          NOT NULL," +
            "  url          VARCHAR(255) NOT NULL," +
            "  FOREIGN KEY (foodtruck_id) REFERENCES foodtruck(id)" +
            ")");
        // 既存 subicon データを新テーブルに移行
        if (columnExists(conn, "foodtruck", "subicon")) {
            exec(conn,
                "INSERT INTO foodtruck_subicon (foodtruck_id, url) " +
                "SELECT id, subicon FROM foodtruck WHERE subicon IS NOT NULL");
            dropColumnIfExists(conn, "foodtruck", "subicon");
        }
        logger.info("Created foodtruck_subicon table and migrated subicon data");
    }

    private static void migrateV19(Connection conn) throws Exception {
        addColumnIfMissing(conn, "projects", "bookmark_count", "INT NOT NULL DEFAULT 0");
        addColumnIfMissing(conn, "foodtruck", "bookmark_count", "INT NOT NULL DEFAULT 0");

        exec(conn,
            "CREATE TABLE IF NOT EXISTS project_stars (" +
            "  id         BIGINT   AUTO_INCREMENT PRIMARY KEY," +
            "  project_id INT      NOT NULL," +
            "  created_at DATETIME NOT NULL," +
            "  FOREIGN KEY (project_id) REFERENCES projects(id)" +
            ")");

        exec(conn,
            "CREATE TABLE IF NOT EXISTS foodtruck_stars (" +
            "  id           BIGINT   AUTO_INCREMENT PRIMARY KEY," +
            "  foodtruck_id INT      NOT NULL," +
            "  created_at   DATETIME NOT NULL," +
            "  FOREIGN KEY (foodtruck_id) REFERENCES foodtruck(id)" +
            ")");
        logger.info("Created project_stars and foodtruck_stars tables, added bookmark_count columns");
    }

    private static void migrateV20(Connection conn) throws Exception {
        exec(conn,
            "CREATE TABLE IF NOT EXISTS project_delays (" +
            "  project_id    INT          NOT NULL PRIMARY KEY," +
            "  delay_minutes SMALLINT     NULL," +
            "  note          VARCHAR(255) NULL," +
            "  updated_at    DATETIME     NOT NULL," +
            "  updated_by    VARCHAR(255) NOT NULL" +
            ")");
        logger.info("Created project_delays table");
    }

    private static void migrateV22(Connection conn) throws Exception {
        migrateV22Stars(conn, "project_stars",  "project_id",   "idx_project_id",
                        "project_stars_ibfk_1",  "projects",     "ux_project_stars_device");
        migrateV22Stars(conn, "foodtruck_stars", "foodtruck_id", "idx_foodtruck_id",
                        "foodtruck_stars_ibfk_1", "foodtruck",    "ux_foodtruck_stars_device");
    }

    private static void migrateV22Stars(Connection conn,
            String table, String fkCol, String idxName,
            String fkName, String refTable, String uniqueIdxName) throws Exception {
        // 識別子はハードコード値のみ想定。万一将来 refactor で外部値が混入した場合の防御バリデーション。
        validateIdentifier(table); validateIdentifier(fkCol); validateIdentifier(idxName);
        validateIdentifier(fkName); validateIdentifier(refTable); validateIdentifier(uniqueIdxName);
        if (columnExists(conn, table, "device_id")) {
            // FK を先に DROP しないと device_id を含む UNIQUE index を削除できない
            dropFkIfExists(conn, table, fkName);
            dropColumnIfExists(conn, table, "device_id");
            // device_id DROP 後も UNIQUE index が残る場合（MySQL が自動削除しない場合）に備える
            dropIndexIfExists(conn, table, uniqueIdxName);
        } else {
            // device_id は既に消えているが UNIQUE index が orphan で残っている可能性
            dropFkIfExists(conn, table, fkName);
            dropIndexIfExists(conn, table, uniqueIdxName);
        }
        // 非 UNIQUE index を保証（FK backing + クエリ性能）
        if (!indexExists(conn, table, idxName)) {
            exec(conn, "ALTER TABLE `" + table + "` ADD INDEX `" + idxName + "` (`" + fkCol + "`)");
            logger.info("Added {} on {}", idxName, table);
        }
        // FK を再作成
        if (!fkExists(conn, table, fkName)) {
            exec(conn, "ALTER TABLE `" + table + "` ADD CONSTRAINT `" + fkName + "` " +
                       "FOREIGN KEY (`" + fkCol + "`) REFERENCES `" + refTable + "` (id)");
            logger.info("Re-added FK {} on {}", fkName, table);
        }
    }

    private static void dropFkIfExists(Connection conn, String table, String fkName) throws Exception {
        if (fkExists(conn, table, fkName)) {
            exec(conn, "ALTER TABLE `" + table + "` DROP FOREIGN KEY `" + fkName + "`");
            logger.info("Dropped FK {} from {}", fkName, table);
        }
    }

    private static void dropIndexIfExists(Connection conn, String table, String idxName) throws Exception {
        if (indexExists(conn, table, idxName)) {
            exec(conn, "ALTER TABLE `" + table + "` DROP INDEX `" + idxName + "`");
            logger.info("Dropped index {} from {}", idxName, table);
        }
    }

    private static boolean indexExists(Connection conn, String table, String idxName) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.STATISTICS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?")) {
            ps.setString(1, table);
            ps.setString(2, idxName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void validateIdentifier(String name) {
        if (name == null || !name.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + name);
        }
    }

    private static boolean fkExists(Connection conn, String table, String fkName) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.TABLE_CONSTRAINTS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? " +
                "AND CONSTRAINT_NAME = ? AND CONSTRAINT_TYPE = 'FOREIGN KEY'")) {
            ps.setString(1, table);
            ps.setString(2, fkName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void migrateV23(Connection conn) throws Exception {
        // Add type column to locations to distinguish rooms from food trucks
        addColumnIfMissing(conn, "locations", "type", "VARCHAR(20) NOT NULL DEFAULT 'room'");

        // Insert food truck locations (floor=0 means outdoor/foodtruck area)
        String insertLoc =
            "INSERT IGNORE INTO locations (id, name, floor, location_code, type) VALUES ";
        exec(conn, insertLoc +
            "(100,'Happy Smile',0,'hs','foodtruck')," +
            "(101,'和氷屋',0,'kg','foodtruck')," +
            "(102,'ましゅれ①',0,'ms','foodtruck')," +
            "(103,'鉄板キング',0,'tk','foodtruck')," +
            "(104,'ましゅれ②',0,'msb','foodtruck')," +
            "(105,'Potato Friend''s',0,'pf','foodtruck')," +
            "(106,'ポテタコTANAKA',0,'pt','foodtruck')," +
            "(107,'カルボ',0,'cb','foodtruck')," +
            "(108,'kitakitsunekitchen',0,'kk','foodtruck')," +
            "(109,'Big mam''s pie',0,'bmp','foodtruck')," +
            "(110,'Kitakara',0,'ktk','foodtruck')," +
            "(111,'夕張マルシェ',0,'ym','foodtruck')," +
            "(112,'LUCKY FOOD DELI',0,'lfd','foodtruck')," +
            "(113,'AGE-MON屋',0,'am','foodtruck')," +
            "(114,'リトルダイニング',0,'ld','foodtruck')," +
            "(115,'おやRITS',0,'or','foodtruck')," +
            "(116,'8A GARAGE COFFEE',0,'8a','foodtruck')"
        );
        logger.info("Inserted food truck locations");

        // Seed initial congestion_status for all food trucks (level=0)
        String insertCs =
            "INSERT IGNORE INTO congestion_status (location_code, level, updated_at, updated_by) VALUES ";
        exec(conn, insertCs +
            "('hs',0,NOW(),'system')," +
            "('kg',0,NOW(),'system')," +
            "('ms',0,NOW(),'system')," +
            "('tk',0,NOW(),'system')," +
            "('msb',0,NOW(),'system')," +
            "('pf',0,NOW(),'system')," +
            "('pt',0,NOW(),'system')," +
            "('cb',0,NOW(),'system')," +
            "('kk',0,NOW(),'system')," +
            "('bmp',0,NOW(),'system')," +
            "('ktk',0,NOW(),'system')," +
            "('ym',0,NOW(),'system')," +
            "('lfd',0,NOW(),'system')," +
            "('am',0,NOW(),'system')," +
            "('ld',0,NOW(),'system')," +
            "('or',0,NOW(),'system')," +
            "('8a',0,NOW(),'system')"
        );

        // Seed congestion_status for existing room locations not yet tracked
        // (食堂, 1F/2F classrooms that were missing)
        exec(conn, insertCs +
            "('LR',0,NOW(),'system')," +
            "('1A',0,NOW(),'system')," +
            "('1C',0,NOW(),'system')," +
            "('1F',0,NOW(),'system')," +
            "('1H',0,NOW(),'system')," +
            "('1J',0,NOW(),'system')," +
            "('2C',0,NOW(),'system')," +
            "('2F',0,NOW(),'system')," +
            "('2H',0,NOW(),'system')," +
            "('IR',0,NOW(),'system')," +
            "('CC',0,NOW(),'system')"
        );
        logger.info("Seeded congestion_status for food trucks and additional room locations");
    }

    private static void migrateV24(Connection conn) throws Exception {
        // アトリウム会場を追加 (中2企画)
        exec(conn,
            "INSERT IGNORE INTO locations (id, name, floor, location_code, type) VALUES " +
            "(117,'アトリウム',1,'AT','room')"
        );
        exec(conn,
            "INSERT IGNORE INTO congestion_status (location_code, level, updated_at, updated_by) VALUES " +
            "('AT',0,NOW(),'system')"
        );
        logger.info("Added アトリウム location and congestion_status entry");
    }

    private static void migrateV25(Connection conn) throws Exception {
        // すべてのテーブルの照合順序を utf8mb4_unicode_ci に統一
        // projects, foodtruck, metrics_hourly, metrics_endpoints, operation_logs 等
        // 大量データのメトリクス系テーブルは除外（ALTER TABLE が遅く起動タイムアウトの原因になる）
        String[] tables = {
            "projects", "foodtruck",
            "announcements", "bus", "credit", "locations", "categories", "congestion_status",
            "menus", "timetables", "project_categories", "project_stars", "project_delays",
            "foodtruck_stars", "foodtruck_sns", "foodtruck_subicon",
            "static_data", "seed_version"
        };
        
        for (String table : tables) {
            try {
                exec(conn, "ALTER TABLE " + table + " CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                logger.info("Unified collation for table: " + table);
            } catch (Exception e) {
                logger.warn("Failed to convert collation for " + table + ": " + e.getMessage());
            }
        }
    }

    private static void migrateV26(Connection conn) throws Exception {
        addColumnIfMissing(conn, "projects", "blurhash", "VARCHAR(100)");
    }

    private static void migrateV27(Connection conn) throws Exception {
        addColumnIfMissing(conn, "foodtruck", "blurhash", "VARCHAR(100)");
    }

    private static void migrateV28(Connection conn) throws Exception {
        addColumnIfMissing(conn, "menus", "blurhash", "VARCHAR(100)");
        addColumnIfMissing(conn, "foodtruck_subicon", "blurhash", "VARCHAR(100)");
    }

    private static void migrateV21(Connection conn) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.STATISTICS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project_stars' " +
                "AND INDEX_NAME = 'idx_project_id'")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    exec(conn, "ALTER TABLE project_stars ADD INDEX idx_project_id (project_id)");
                    logger.info("Added idx_project_id on project_stars");
                } else {
                    logger.info("idx_project_id on project_stars already exists, skipping");
                }
            }
        }
    }

    // ── util ──────────────────────────────────────────────────

    private static boolean columnExists(Connection conn, String table, String column) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void dropColumnIfExists(Connection conn, String table, String column) throws Exception {
        if (columnExists(conn, table, column)) {
            exec(conn, "ALTER TABLE `" + table + "` DROP COLUMN `" + column + "`");
            logger.info("Dropped {} from {}", column, table);
        }
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (Statement s = conn.createStatement()) {
            s.execute(sql);
        }
    }

    private static boolean tableExists(Connection conn, String table) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
