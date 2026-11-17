CREATE TABLE review_appeal (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    review_id BIGINT NOT NULL UNIQUE,
    doctor_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    decision_note VARCHAR(500),
    decided_by BIGINT,
    decided_at DATETIME(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_appeal_status (status, id),
    FOREIGN KEY (review_id) REFERENCES review(id),
    FOREIGN KEY (doctor_id) REFERENCES doctor_profile(id),
    FOREIGN KEY (decided_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
