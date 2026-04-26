package dev.gate;

import dev.gate.core.Database;
import dev.gate.core.Logger;

import java.sql.*;

public class DataSeeder {
    private static final Logger logger = new Logger(DataSeeder.class);

    public static void seed() throws Exception {
        try (Connection conn = Database.getConnection()) {
            if (getSeedVersion(conn) >= 1) {
                logger.info("Seed data already present (v1) — skipping");
                return;
            }
            seedFestival(conn);
            seedDays(conn);
            seedVenuesAndCategories(conn);
            seedEvents(conn);
            seedFood(conn);
            seedMap(conn);
            seedAnnouncements(conn);
            setSeedVersion(conn, 1);
            logger.info("Demo seed data (v1) inserted");
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

    // ── festival / days ───────────────────────────────────────

    private static void seedFestival(Connection conn) throws Exception {
        exec(conn,
            "INSERT IGNORE INTO festival (id, title, theme) VALUES " +
            "('rsai2026', '立命祭2026 第31回', '（テーマ未定）')");
    }

    private static void seedDays(Connection conn) throws Exception {
        exec(conn,
            "INSERT IGNORE INTO days (id, date, label) VALUES " +
            "('day1', '2026-07-04', '1日目（7/4）'), " +
            "('day2', '2026-07-05', '2日目（7/5）')");
    }

    // ── venues / categories ───────────────────────────────────

    private static void seedVenuesAndCategories(Connection conn) throws Exception {
        exec(conn,
            "INSERT IGNORE INTO event_venues (id, name, capacity, category) VALUES " +
            "('venue-gym',     '体育館',            500,  'stage'), " +
            "('venue-stage',   'メインステージ',     300,  'stage'), " +
            "('venue-3a',      '3-A教室',             40,  'classroom'), " +
            "('venue-3b',      '3-B教室',             40,  'classroom'), " +
            "('venue-3c',      '3-C教室',             40,  'classroom'), " +
            "('venue-4a',      '4-A教室',             40,  'classroom'), " +
            "('venue-4b',      '4-B教室',             40,  'classroom'), " +
            "('venue-yard',    '中庭',               200,  'outdoor'), " +
            "('venue-kitchen', 'キッチンカーエリア', NULL,  'food'), " +
            "('venue-gate',    '正門前広場',          300,  'outdoor')");

        exec(conn,
            "INSERT IGNORE INTO event_categories (id, label) VALUES " +
            "('general', '一般'), " +
            "('stage',   'ステージ・パフォーマンス'), " +
            "('drama',   '演劇'), " +
            "('exhibit', '展示・体験'), " +
            "('food',    '飲食')");
    }

    // ── events ────────────────────────────────────────────────

    private static void seedEvents(Connection conn) throws Exception {
        // day1
        exec(conn,
            "INSERT IGNORE INTO events (id, day_id, venue_id, start_time, end_time, category_id, title) VALUES " +
            "('e01', 'day1', 'venue-gym',   '09:00:00', '09:30:00', 'general', '開会式'), " +
            "('e02', 'day1', 'venue-stage', '10:00:00', '10:45:00', 'stage',   'ステージ企画①（タイトル未定）'), " +
            "('e03', 'day1', 'venue-stage', '11:00:00', '11:45:00', 'stage',   'ステージ企画②（タイトル未定）'), " +
            "('e04', 'day1', 'venue-stage', '13:00:00', '13:45:00', 'stage',   'ステージ企画③（タイトル未定）'), " +
            "('e05', 'day1', 'venue-stage', '14:00:00', '14:45:00', 'stage',   'ステージ企画④（タイトル未定）'), " +
            "('e06', 'day1', 'venue-3a',    '10:00:00', '16:00:00', 'exhibit', 'クラス企画（未定）— 3-A'), " +
            "('e07', 'day1', 'venue-3b',    '10:00:00', '16:00:00', 'exhibit', 'クラス企画（未定）— 3-B'), " +
            "('e08', 'day1', 'venue-3c',    '10:00:00', '16:00:00', 'exhibit', 'クラス企画（未定）— 3-C'), " +
            "('e09', 'day1', 'venue-4a',    '10:00:00', '16:00:00', 'exhibit', 'クラス企画（未定）— 4-A'), " +
            "('e10', 'day1', 'venue-4b',    '10:00:00', '16:00:00', 'drama',   '演劇（タイトル未定）— 4-B'), " +
            "('e11', 'day1', 'venue-yard',  '12:00:00', '13:30:00', 'stage',   '野外ステージ（未定）')");

        // day2
        exec(conn,
            "INSERT IGNORE INTO events (id, day_id, venue_id, start_time, end_time, category_id, title) VALUES " +
            "('e21', 'day2', 'venue-gym',   '10:00:00', '10:45:00', 'stage',   'ステージ企画⑤（タイトル未定）'), " +
            "('e22', 'day2', 'venue-gym',   '11:00:00', '11:45:00', 'stage',   'ステージ企画⑥（タイトル未定）'), " +
            "('e23', 'day2', 'venue-stage', '10:00:00', '10:45:00', 'stage',   'ステージ企画⑦（タイトル未定）'), " +
            "('e24', 'day2', 'venue-stage', '13:00:00', '13:45:00', 'drama',   '演劇（タイトル未定）'), " +
            "('e25', 'day2', 'venue-3a',    '10:00:00', '16:00:00', 'exhibit', 'クラス企画（未定）— 3-A'), " +
            "('e26', 'day2', 'venue-3b',    '10:00:00', '16:00:00', 'exhibit', 'クラス企画（未定）— 3-B'), " +
            "('e27', 'day2', 'venue-3c',    '10:00:00', '16:00:00', 'exhibit', 'クラス企画（未定）— 3-C'), " +
            "('e28', 'day2', 'venue-4a',    '10:00:00', '16:00:00', 'exhibit', 'クラス企画（未定）— 4-A'), " +
            "('e29', 'day2', 'venue-4b',    '10:00:00', '16:00:00', 'drama',   '演劇（タイトル未定）— 4-B'), " +
            "('e30', 'day2', 'venue-gym',   '15:30:00', '16:00:00', 'general', '閉会式')");
    }

    // ── food ──────────────────────────────────────────────────

    private static void seedFood(Connection conn) throws Exception {
        exec(conn,
            "INSERT IGNORE INTO vendors (id, type, name, venue, status, notes, tags, payment) VALUES " +
            "('v1', 'kitchen_car', 'キッチンカー①（出店未定）', 'venue-kitchen', 'open', '詳細は後日お知らせします', '[\"未定\"]', '[\"現金\"]'), " +
            "('v2', 'kitchen_car', 'キッチンカー②（出店未定）', 'venue-kitchen', 'open', '詳細は後日お知らせします', '[\"未定\"]', '[\"現金\"]'), " +
            "('v3', 'class_shop',  'クラス模擬店①（未定）',     'venue-yard',    'open', '詳細は後日お知らせします', '[\"未定\"]', '[\"現金\"]'), " +
            "('v4', 'class_shop',  'クラス模擬店②（未定）',     'venue-yard',    'open', '詳細は後日お知らせします', '[\"未定\"]', '[\"現金\"]')");

        exec(conn,
            "INSERT IGNORE INTO vendor_hours (vendor_id, day_id, open_time, close_time, last_order) VALUES " +
            "('v1', 'day1', '10:00:00', '15:30:00', '15:00:00'), " +
            "('v1', 'day2', '10:00:00', '15:30:00', '15:00:00'), " +
            "('v2', 'day1', '10:00:00', '15:30:00', '15:00:00'), " +
            "('v2', 'day2', '10:00:00', '15:30:00', '15:00:00'), " +
            "('v3', 'day1', '11:00:00', '15:00:00', '14:30:00'), " +
            "('v3', 'day2', '11:00:00', '15:00:00', '14:30:00'), " +
            "('v4', 'day1', '11:00:00', '15:00:00', '14:30:00'), " +
            "('v4', 'day2', '11:00:00', '15:00:00', '14:30:00')");

        exec(conn,
            "INSERT IGNORE INTO dining_areas (id, name, floor, capacity_est, seats_indoor, weather) VALUES " +
            "('area-cafeteria', '食堂エリア',       1,    100, 1, 'indoor'), " +
            "('area-yard',      '中庭飲食エリア',   NULL, 200, 0, 'outdoor')");

        exec(conn,
            "INSERT IGNORE INTO food_rules (id, rule, sort_order) VALUES " +
            "(1, 'ゴミは分別して所定の場所に捨ててください', 1), " +
            "(2, '食べ歩き禁止区域にご注意ください', 2), " +
            "(3, '飲食エリアをご利用ください', 3)");

        exec(conn,
            "INSERT IGNORE INTO eco_stations (id, location_hint, status) VALUES " +
            "('eco-1', '体育館入口付近', 'active'), " +
            "('eco-2', '中庭エリア',     'active')");
    }

    // ── map ───────────────────────────────────────────────────

    private static void seedMap(Connection conn) throws Exception {
        exec(conn,
            "INSERT IGNORE INTO map_config (id, building, pins_version) VALUES " +
            "(1, '立命館学園', 1)");

        exec(conn,
            "INSERT IGNORE INTO floors (id, label) VALUES " +
            "('1f', '1階'), ('2f', '2階'), ('3f', '3階')");

        exec(conn,
            "INSERT IGNORE INTO rooms (id, floor_id, name, category, is_public, capacity) VALUES " +
            "('room-gym',   '1f', '体育館',         'stage',     1, 500), " +
            "('room-stage', '1f', 'メインステージ',  'stage',     1, 300), " +
            "('room-3a',    '2f', '3-A教室',         'classroom', 1,  40), " +
            "('room-3b',    '2f', '3-B教室',         'classroom', 1,  40), " +
            "('room-3c',    '2f', '3-C教室',         'classroom', 1,  40), " +
            "('room-4a',    '3f', '4-A教室',         'classroom', 1,  40), " +
            "('room-4b',    '3f', '4-B教室',         'classroom', 1,  40)");

        exec(conn,
            "INSERT IGNORE INTO outdoor_areas (id, name, category, is_public, weather) VALUES " +
            "('outdoor-yard',    '中庭',              'outdoor', 1, 'open'), " +
            "('outdoor-kitchen', 'キッチンカーエリア', 'food',    1, 'open'), " +
            "('outdoor-gate',    '正門前広場',          'outdoor', 1, 'open')");

        exec(conn,
            "INSERT IGNORE INTO poi (id, name, floor_id, type, is_accessible, note) VALUES " +
            "('poi-toilet-1f', 'トイレ (1F)', '1f', 'restroom', 1, NULL), " +
            "('poi-toilet-2f', 'トイレ (2F)', '2f', 'restroom', 0, NULL), " +
            "('poi-toilet-3f', 'トイレ (3F)', '3f', 'restroom', 0, NULL), " +
            "('poi-nursing',   '救護室',       '1f', 'medical',  1, '体育館横'), " +
            "('poi-info',      '総合案内所',   '1f', 'info',     1, '正門入口')");

        exec(conn,
            "INSERT IGNORE INTO map_notes (id, note, sort_order) VALUES " +
            "(1, '会場マップはデモ表示です。実際のレイアウトと異なる場合があります。', 1)");
    }

    // ── announcements ─────────────────────────────────────────

    private static void seedAnnouncements(Connection conn) throws Exception {
        exec(conn,
            "INSERT IGNORE INTO announcements (id, title, body, is_emergency) VALUES " +
            "(1, 'ここにお知らせを表示できます', 'このエリアにお知らせの本文を表示できます。（テスト表示）', 0), " +
            "(2, '【緊急】ここに緊急お知らせを表示できます', '緊急お知らせの内容がここに表示されます。（テスト表示）', 1)");
    }

    // ── util ──────────────────────────────────────────────────

    private static void exec(Connection conn, String sql) throws Exception {
        try (Statement s = conn.createStatement()) {
            s.execute(sql);
        }
    }
}
