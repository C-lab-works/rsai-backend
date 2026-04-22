CREATE TABLE IF NOT EXISTS schedules (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    title      VARCHAR(255)  NOT NULL,
    start_at   DATETIME      NOT NULL,
    end_at     DATETIME      NOT NULL,
    created_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
);
