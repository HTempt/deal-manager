ALTER TABLE customer ADD COLUMN funnel_stage_rank INT NOT NULL DEFAULT 0;
CREATE INDEX idx_customer_funnel_cohort ON customer(user_id, created_at, status);

-- Existing LOST customers whose stage was overwritten with 已流失 have no recoverable
-- historical stage. They start at 0; subsequent updates retain the highest reached rank.
UPDATE customer
SET funnel_stage_rank = CASE
    WHEN status = 'DEAL' OR stage = '已成交' THEN 5
    WHEN stage = '谈判中' THEN 4
    WHEN stage = '报价中' THEN 3
    WHEN stage = '方案中' THEN 2
    WHEN stage = '需求沟通' THEN 1
    ELSE 0
END;
