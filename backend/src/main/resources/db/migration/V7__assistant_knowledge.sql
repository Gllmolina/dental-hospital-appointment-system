CREATE TABLE knowledge_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    family_id BIGINT,
    category VARCHAR(80) NOT NULL,
    question VARCHAR(500) NOT NULL,
    answer VARCHAR(2000) NOT NULL,
    keywords VARCHAR(500) NOT NULL DEFAULT '',
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    version INT NOT NULL DEFAULT 1,
    approved_by BIGINT,
    approved_at DATETIME(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_knowledge_status (status, id),
    KEY idx_knowledge_family (family_id, version),
    FOREIGN KEY (approved_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    patient_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    deleted_at DATETIME(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_ai_session_patient (patient_id, deleted_at, id),
    FOREIGN KEY (patient_id) REFERENCES patient_profile(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    content VARCHAR(2500) NOT NULL,
    source VARCHAR(32),
    knowledge_id BIGINT,
    elapsed_ms INT,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_ai_message_session (session_id, id),
    FOREIGN KEY (session_id) REFERENCES ai_session(id),
    FOREIGN KEY (knowledge_id) REFERENCES knowledge_item(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_feedback (
    patient_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    helpful BOOLEAN NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (patient_id, message_id),
    FOREIGN KEY (patient_id) REFERENCES patient_profile(id),
    FOREIGN KEY (message_id) REFERENCES ai_message(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
