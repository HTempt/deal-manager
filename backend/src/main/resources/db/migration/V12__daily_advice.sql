CREATE TABLE IF NOT EXISTS daily_advice (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    advice_date DATE NOT NULL,
    overview TEXT NOT NULL,
    items MEDIUMTEXT NOT NULL,
    candidate_count INT NOT NULL DEFAULT 0,
    source_hash CHAR(64) NOT NULL,
    prompt MEDIUMTEXT NOT NULL,
    model VARCHAR(100) NOT NULL,
    token_usage INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_daily_advice_user_date (user_id, advice_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;