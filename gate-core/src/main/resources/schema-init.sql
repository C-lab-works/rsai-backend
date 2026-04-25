-- ============================================================
-- rsai-backend normalized schema  (MySQL 8.4)
-- Executed by SchemaManager.java (one statement at a time)
-- All statements are idempotent (CREATE TABLE IF NOT EXISTS)
-- ============================================================

CREATE TABLE IF NOT EXISTS seed_version (
    id      INT PRIMARY KEY DEFAULT 1,
    version INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS festival (
    id      VARCHAR(50)  PRIMARY KEY,
    title   TEXT         NOT NULL,
    theme   TEXT
);

CREATE TABLE IF NOT EXISTS days (
    id      VARCHAR(10)  PRIMARY KEY,
    date    DATE         NOT NULL,
    label   TEXT         NOT NULL
);

CREATE TABLE IF NOT EXISTS event_venues (
    id          VARCHAR(50)  PRIMARY KEY,
    name        TEXT         NOT NULL,
    capacity    INT,
    category    VARCHAR(20)
);

CREATE TABLE IF NOT EXISTS event_categories (
    id      VARCHAR(50)  PRIMARY KEY,
    label   TEXT         NOT NULL
);

CREATE TABLE IF NOT EXISTS events (
    id           VARCHAR(50)  PRIMARY KEY,
    day_id       VARCHAR(10)  NOT NULL,
    venue_id     VARCHAR(50)  NOT NULL,
    start_time   TIME         NOT NULL,
    end_time     TIME         NOT NULL,
    category_id  VARCHAR(50),
    title        TEXT         NOT NULL,
    description  TEXT,
    audience     TEXT,
    multi_venue  TINYINT(1)   NOT NULL DEFAULT 0,
    FOREIGN KEY (day_id)      REFERENCES days(id),
    FOREIGN KEY (venue_id)    REFERENCES event_venues(id),
    FOREIGN KEY (category_id) REFERENCES event_categories(id)
);

-- ===== FOOD =====

CREATE TABLE IF NOT EXISTS vendors (
    id          VARCHAR(100) PRIMARY KEY,
    type        VARCHAR(50)  NOT NULL,
    name        TEXT         NOT NULL,
    venue       VARCHAR(100),
    status      VARCHAR(20)  NOT NULL,
    notes       TEXT,
    tags        JSON,
    payment     JSON
);

CREATE TABLE IF NOT EXISTS vendor_hours (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    vendor_id   VARCHAR(100) NOT NULL,
    day_id      VARCHAR(10)  NOT NULL,
    open_time   TIME         NOT NULL,
    close_time  TIME         NOT NULL,
    last_order  TIME,
    UNIQUE KEY uq_vendor_day (vendor_id, day_id),
    FOREIGN KEY (vendor_id) REFERENCES vendors(id),
    FOREIGN KEY (day_id)    REFERENCES days(id)
);

CREATE TABLE IF NOT EXISTS dining_areas (
    id              VARCHAR(50)  PRIMARY KEY,
    name            TEXT         NOT NULL,
    floor           INT,
    capacity_est    INT,
    seats_indoor    TINYINT(1),
    weather         VARCHAR(50),
    tables_json     JSON
);

CREATE TABLE IF NOT EXISTS food_rules (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    rule        TEXT         NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS eco_stations (
    id              VARCHAR(50)  PRIMARY KEY,
    location_hint   TEXT,
    status          VARCHAR(50)
);

-- ===== MAP =====

CREATE TABLE IF NOT EXISTS floors (
    id      VARCHAR(10)  PRIMARY KEY,
    label   TEXT         NOT NULL
);

CREATE TABLE IF NOT EXISTS rooms (
    id          VARCHAR(100) PRIMARY KEY,
    floor_id    VARCHAR(10)  NOT NULL,
    name        TEXT         NOT NULL,
    use_desc    TEXT,
    category    VARCHAR(50),
    is_public   TINYINT(1)   NOT NULL DEFAULT 0,
    capacity    INT,
    tags        JSON,
    FOREIGN KEY (floor_id) REFERENCES floors(id)
);

CREATE TABLE IF NOT EXISTS outdoor_areas (
    id          VARCHAR(50)  PRIMARY KEY,
    name        TEXT         NOT NULL,
    use_desc    TEXT,
    category    VARCHAR(50),
    is_public   TINYINT(1)   NOT NULL DEFAULT 0,
    weather     VARCHAR(50)
);

CREATE TABLE IF NOT EXISTS poi (
    id            VARCHAR(100) PRIMARY KEY,
    name          TEXT         NOT NULL,
    floor_id      VARCHAR(10),
    type          VARCHAR(50)  NOT NULL,
    is_accessible TINYINT(1)   NOT NULL DEFAULT 0,
    room_ref      VARCHAR(100),
    note          TEXT
);

CREATE TABLE IF NOT EXISTS map_config (
    id           INT  PRIMARY KEY DEFAULT 1,
    building     TEXT NOT NULL,
    pins_version INT  NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS map_notes (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    note        TEXT NOT NULL,
    sort_order  INT  NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS locations (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    floor       VARCHAR(20)  NOT NULL,
    category    VARCHAR(50),
    map_x       FLOAT        NOT NULL DEFAULT 0,
    map_y       FLOAT        NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS congestion_status (
    location_id INT          PRIMARY KEY,
    level       TINYINT      NOT NULL DEFAULT 0,
    updated_at  DATETIME     NOT NULL,
    updated_by  VARCHAR(100) NOT NULL
);
