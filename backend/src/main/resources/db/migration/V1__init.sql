CREATE TABLE IF NOT EXISTS `user` (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    openid VARCHAR(64) NOT NULL UNIQUE,
    nickname VARCHAR(100),
    avatar_url VARCHAR(500),
    phone VARCHAR(20),
    industry VARCHAR(50),
    membership_type VARCHAR(20) NOT NULL DEFAULT 'FREE',
    membership_expire_time DATETIME,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS customer (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    phone VARCHAR(20),
    need_text TEXT,
    budget DECIMAL(12,2),
    source VARCHAR(50),
    stage VARCHAR(30) NOT NULL DEFAULT '新客户',
    intention_score INT NOT NULL DEFAULT 10,
    follow_count INT NOT NULL DEFAULT 0,
    last_follow_time DATETIME,
    next_follow_time DATETIME,
    deal_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
    deal_time DATETIME,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_customer_user(user_id),
    INDEX idx_customer_next_follow(next_follow_time),
    INDEX idx_customer_intention(intention_score),
    INDEX idx_customer_status(user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS follow_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    follow_type VARCHAR(20),
    content TEXT,
    customer_feedback VARCHAR(50),
    intention_change INT NOT NULL DEFAULT 0,
    next_follow_time DATETIME,
    created_at DATETIME NOT NULL,
    INDEX idx_follow_record_customer(customer_id),
    INDEX idx_follow_record_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS follow_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    task_type VARCHAR(30),
    title VARCHAR(200),
    plan_time DATETIME NOT NULL,
    completed_time DATETIME,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME NOT NULL,
    INDEX idx_follow_task_user_status(user_id, status),
    INDEX idx_follow_task_plan(plan_time),
    INDEX idx_follow_task_customer(customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ai_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    customer_id BIGINT,
    scenario VARCHAR(50),
    prompt TEXT,
    response TEXT,
    model VARCHAR(50),
    token_usage INT,
    created_at DATETIME NOT NULL,
    INDEX idx_ai_record_user_time(user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS deal (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    product_name VARCHAR(200),
    deal_time DATETIME NOT NULL,
    source VARCHAR(50),
    created_at DATETIME NOT NULL,
    INDEX idx_deal_user_time(user_id, deal_time),
    INDEX idx_deal_customer(customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS industry_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    industry_code VARCHAR(50) NOT NULL UNIQUE,
    industry_name VARCHAR(100) NOT NULL,
    stages JSON,
    ai_scenarios JSON,
    created_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_ai_setting (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL UNIQUE,
    reply_style VARCHAR(30) NOT NULL DEFAULT '自然亲切',
    reply_length VARCHAR(20) NOT NULL DEFAULT '适中',
    preferred_address VARCHAR(30) NOT NULL DEFAULT '自动判断',
    business_intro TEXT,
    business_advantage TEXT,
    common_expression TEXT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO industry_config(industry_code, industry_name, stages, ai_scenarios, created_at) VALUES
('DECORATION','装修', JSON_ARRAY('新客户','需求沟通','方案中','报价中','谈判中','已成交','已流失'), JSON_ARRAY('首次跟进','报价后跟进','客户嫌贵','客户不回复','促成成交','唤醒客户'), NOW()),
('WEDDING','婚庆', JSON_ARRAY('婚期咨询','套餐沟通','档期确认','报价','定金','已成交','已流失'), JSON_ARRAY('首次跟进','报价后跟进','客户嫌贵','客户不回复','促成成交'), NOW()),
('PHOTOGRAPHY','摄影', JSON_ARRAY('新客户','需求沟通','方案确认','报价中','已成交','已流失'), JSON_ARRAY('首次跟进','报价后跟进','客户不回复','促成成交'), NOW());
