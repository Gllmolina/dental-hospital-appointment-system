CREATE TABLE verification_challenge (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    destination CHAR(11) NOT NULL,
    code_digest CHAR(64) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    used_at DATETIME(3),
    attempt_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_challenge_lookup (user_id,purpose,created_at),
    FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CHECK (attempt_count BETWEEN 0 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE verification_rate_guard (
    guard_key CHAR(64) PRIMARY KEY,
    window_started_at DATETIME(3) NOT NULL,
    attempts INT NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
