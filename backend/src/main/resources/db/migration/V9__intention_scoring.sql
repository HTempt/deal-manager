ALTER TABLE customer ADD COLUMN intention_mode VARCHAR(10) NOT NULL DEFAULT 'AUTO' AFTER intention_score;
ALTER TABLE follow_record
    ADD COLUMN active_inquiry BOOLEAN NOT NULL DEFAULT FALSE AFTER customer_feedback,
    ADD COLUMN price_inquiry BOOLEAN NOT NULL DEFAULT FALSE AFTER active_inquiry,
    ADD COLUMN intention_before INT NULL AFTER intention_change,
    ADD COLUMN intention_after INT NULL AFTER intention_before,
    ADD COLUMN intention_mode VARCHAR(10) NULL AFTER intention_after;
