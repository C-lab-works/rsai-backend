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
    level       TINYINT      NOT NULL DEFAULT 0,
    updated_at  DATETIME     NOT NULL,
    updated_by  VARCHAR(100) NOT NULL
);
