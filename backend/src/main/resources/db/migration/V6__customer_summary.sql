CREATE TABLE IF NOT EXISTS customer_summary (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    overview TEXT NOT NULL,
    suggestion TEXT NOT NULL,
    source_hash CHAR(64) NOT NULL,
    records_used INT NOT NULL DEFAULT 0,
    prompt MEDIUMTEXT NOT NULL,
    model VARCHAR(100) NOT NULL,
    token_usage INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_customer_summary_owner (user_id, customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
