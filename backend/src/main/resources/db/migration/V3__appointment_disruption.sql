CREATE TABLE appointment_disruption (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    appointment_id BIGINT NOT NULL,
    suspended_schedule_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    resolved_at DATETIME(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_disruption_appointment_schedule (appointment_id, suspended_schedule_id),
    KEY idx_disruption_status (status, id),
    FOREIGN KEY (appointment_id) REFERENCES appointment(id),
    FOREIGN KEY (suspended_schedule_id) REFERENCES schedule(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_appointment_slot_status ON appointment(slot_id, status);
CREATE INDEX idx_schedule_status ON schedule(status);

CREATE TABLE system_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    receiver_id BIGINT NOT NULL,
    appointment_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    title VARCHAR(100) NOT NULL,
    content VARCHAR(500) NOT NULL,
    read_at DATETIME(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_message_receiver (receiver_id, id),
    FOREIGN KEY (receiver_id) REFERENCES sys_user(id),
    FOREIGN KEY (appointment_id) REFERENCES appointment(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

