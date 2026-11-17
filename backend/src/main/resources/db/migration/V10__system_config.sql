CREATE TABLE system_config (
    config_key VARCHAR(80) PRIMARY KEY,
    config_value VARCHAR(200) NOT NULL,
    version INT NOT NULL DEFAULT 1,
    updated_by BIGINT,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    FOREIGN KEY (updated_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO system_config(config_key,config_value) VALUES
('booking.windowDays','7'),
('booking.dailyLimit','3'),
('booking.holdMinutes','15'),
('booking.cancelBeforeMinutes','120'),
('booking.checkInEarlyMinutes','30'),
('schedule.slotMinutes','30');
