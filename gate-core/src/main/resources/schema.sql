-- rsai-backend  (MySQL 8.4)
-- Stable infrastructure tables only.
-- Application tables (categories, locations, projects, timetables, announcements)
-- are created and migrated by DataSeeder at startup.

CREATE TABLE IF NOT EXISTS seed_version (
    id      INT PRIMARY KEY DEFAULT 1,
    version INT NOT NULL DEFAULT 0
);

-- congestion_status は DataSeeder.defineTables が現行形（location_code 主キー）で作成する。
-- 以前ここで旧形（location_id 主キー）を定義していたが、それだと新規DBで毎回
-- migrateV11 の変換が走っていたため削除した。

CREATE TABLE IF NOT EXISTS metrics_hourly (
    hour     BIGINT PRIMARY KEY,
    requests BIGINT NOT NULL DEFAULT 0,
    errors   BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS metrics_latency_histogram (
    bucket_ms INT    PRIMARY KEY,
    count     BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS metrics_endpoints (
    endpoint VARCHAR(250) NOT NULL,
    date     DATE         NOT NULL DEFAULT '2000-01-01',
    hits     BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (endpoint, date)
);

CREATE TABLE IF NOT EXISTS credit (
    id   INT          AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS operation_logs (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    user          VARCHAR(255) NOT NULL,
    action        VARCHAR(50)  NOT NULL,
    target        VARCHAR(255) NOT NULL DEFAULT '',
    detail        TEXT,
    result        VARCHAR(20)  NOT NULL DEFAULT 'ok',
    error_message TEXT,
    timestamp     DATETIME     NOT NULL,
    INDEX idx_timestamp (timestamp),
    INDEX idx_user      (user),
    INDEX idx_action    (action)
);
