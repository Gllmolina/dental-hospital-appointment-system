CREATE TABLE leave_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    doctor_id BIGINT NOT NULL,
    starts_at DATETIME(3) NOT NULL,
    ends_at DATETIME(3) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    reviewed_by BIGINT,
    review_note VARCHAR(500),
    reviewed_at DATETIME(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_leave_doctor (doctor_id, status, starts_at),
    KEY idx_leave_status (status, id),
    FOREIGN KEY (doctor_id) REFERENCES doctor_profile(id),
    FOREIGN KEY (reviewed_by) REFERENCES sys_user(id),
    CHECK (starts_at < ends_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
