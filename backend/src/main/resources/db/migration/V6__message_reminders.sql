ALTER TABLE system_message
    ADD COLUMN dedupe_key VARCHAR(120) NULL UNIQUE,
    ADD COLUMN revoked_at DATETIME(3) NULL,
    ADD KEY idx_message_active (receiver_id, revoked_at, read_at, id);
