ALTER TABLE auth_session ADD COLUMN jti VARCHAR(40) NULL;
CREATE UNIQUE INDEX uq_auth_session_jti ON auth_session(jti);
ALTER TABLE appointment ADD COLUMN called_at DATETIME(3) NULL;
CREATE TABLE admin_operation_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  operator_id BIGINT NOT NULL,
  action VARCHAR(60) NOT NULL,
  target_type VARCHAR(40) NOT NULL,
  target_id BIGINT NOT NULL,
  reason VARCHAR(200),
  safe_summary VARCHAR(500),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_admin_log_operator(operator_id,created_at),
  FOREIGN KEY(operator_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
