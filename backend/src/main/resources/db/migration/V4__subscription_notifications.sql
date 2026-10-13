CREATE TABLE IF NOT EXISTS notification_subscription (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    template_id VARCHAR(100) NOT NULL,
    subscribe_token VARCHAR(512) NOT NULL,
    expires_at DATETIME,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_notification_subscription_user(user_id, status),
    INDEX idx_notification_subscription_expire(expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS notification_send_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    task_id BIGINT,
    notification_type VARCHAR(30) NOT NULL,
    summary_date DATE,
    template_id VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    message VARCHAR(500),
    sent_at DATETIME,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_notification_task_type (task_id, notification_type),
    UNIQUE KEY uk_notification_user_summary (user_id, notification_type, summary_date),
    INDEX idx_notification_send_status(status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
