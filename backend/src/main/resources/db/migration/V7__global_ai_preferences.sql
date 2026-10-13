ALTER TABLE business_summary
    ADD COLUMN ai_setting_hash CHAR(64) NULL,
    MODIFY COLUMN prompt MEDIUMTEXT NOT NULL;
