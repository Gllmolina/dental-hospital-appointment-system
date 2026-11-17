CREATE TABLE health_archive (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    patient_id BIGINT NOT NULL UNIQUE,
    allergy_history VARCHAR(500),
    past_history VARCHAR(500),
    note VARCHAR(500),
    version INT NOT NULL DEFAULT 1,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    FOREIGN KEY (patient_id) REFERENCES patient_profile(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE favorite_doctor (
    patient_id BIGINT NOT NULL,
    doctor_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (patient_id, doctor_id),
    FOREIGN KEY (patient_id) REFERENCES patient_profile(id),
    FOREIGN KEY (doctor_id) REFERENCES doctor_profile(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE review (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    appointment_id BIGINT NOT NULL UNIQUE,
    patient_id BIGINT NOT NULL,
    doctor_id BIGINT NOT NULL,
    service_score TINYINT NOT NULL,
    communication_score TINYINT NOT NULL,
    overall_score TINYINT NOT NULL,
    content VARCHAR(500),
    anonymous BOOLEAN NOT NULL DEFAULT FALSE,
    visibility VARCHAR(24) NOT NULL DEFAULT 'VISIBLE',
    reply VARCHAR(500),
    replied_at DATETIME(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_review_doctor (doctor_id, visibility, id),
    FOREIGN KEY (appointment_id) REFERENCES appointment(id),
    FOREIGN KEY (patient_id) REFERENCES patient_profile(id),
    FOREIGN KEY (doctor_id) REFERENCES doctor_profile(id),
    CHECK (service_score BETWEEN 1 AND 5),
    CHECK (communication_score BETWEEN 1 AND 5),
    CHECK (overall_score BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE feedback (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    reply VARCHAR(1000),
    replied_by BIGINT,
    replied_at DATETIME(3),
    closed_at DATETIME(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_feedback_user (user_id, id),
    KEY idx_feedback_status (status, id),
    FOREIGN KEY (user_id) REFERENCES sys_user(id),
    FOREIGN KEY (replied_by) REFERENCES sys_user(id),
    CHECK (CHAR_LENGTH(content) BETWEEN 10 AND 1000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE system_message MODIFY appointment_id BIGINT NULL;
