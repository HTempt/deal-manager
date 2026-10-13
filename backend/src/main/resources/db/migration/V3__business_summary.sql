CREATE TABLE IF NOT EXISTS business_summary (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    summary_month VARCHAR(7) NOT NULL,
    content TEXT NOT NULL,
    prompt TEXT NOT NULL,
    model VARCHAR(100) NOT NULL,
    token_usage INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_business_summary_user_month (user_id, summary_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
