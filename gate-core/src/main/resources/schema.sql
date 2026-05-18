-- rsai-backend  (MySQL 8.4)
-- Stable infrastructure tables only.
-- Application tables (categories, locations, projects, timetables, announcements)
-- are created and migrated by DataSeeder at startup.

CREATE TABLE IF NOT EXISTS seed_version (
    id      INT PRIMARY KEY DEFAULT 1,
    version INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS congestion_status (
    location_id INT          PRIMARY KEY,
    level       TINYINT(4)   NOT NULL DEFAULT 0,
    updated_at  DATETIME     NOT NULL,
    updated_by  VARCHAR(100) NOT NULL
);

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
    endpoint VARCHAR(250) PRIMARY KEY,
    hits     BIGINT NOT NULL DEFAULT 0
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
