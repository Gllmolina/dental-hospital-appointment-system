CREATE TABLE department
(
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    code        VARCHAR(40) NOT NULL UNIQUE,
    name        VARCHAR(80) NOT NULL,
    description VARCHAR(500),
    sort_order  INT         NOT NULL DEFAULT 0,
    enabled     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE clinic_room
(
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    code       VARCHAR(40) NOT NULL UNIQUE,
    name       VARCHAR(80) NOT NULL,
    enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_user
(
    id                   BIGINT PRIMARY KEY AUTO_INCREMENT,
    username             VARCHAR(32)  NOT NULL UNIQUE,
    phone                CHAR(11)     NOT NULL UNIQUE,
    password_hash        VARCHAR(100) NOT NULL,
    role                 VARCHAR(24)  NOT NULL,
    status               VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    must_change_password BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at           DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE auth_session
(
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id    BIGINT   NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at DATETIME(3) NOT NULL,
    revoked_at DATETIME(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX      idx_session_user(user_id,expires_at),
    FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE patient_profile
(
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL UNIQUE,
    name       VARCHAR(30) NOT NULL,
    gender     VARCHAR(16),
    birthday   DATE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE doctor_profile
(
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id       BIGINT         NOT NULL UNIQUE,
    employee_no   VARCHAR(40)    NOT NULL UNIQUE,
    department_id BIGINT         NOT NULL,
    name          VARCHAR(30)    NOT NULL,
    title         VARCHAR(60),
    specialty     VARCHAR(500),
    intro         VARCHAR(1000),
    default_fee   DECIMAL(10, 2) NOT NULL,
    status        VARCHAR(24)    NOT NULL DEFAULT 'ACTIVE',
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX         idx_doctor_dept(department_id,status),
    FOREIGN KEY (user_id) REFERENCES sys_user (id),
    FOREIGN KEY (department_id) REFERENCES department (id),
    CHECK (default_fee > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE schedule
(
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    doctor_id     BIGINT         NOT NULL,
    department_id BIGINT         NOT NULL,
    room_id       BIGINT         NOT NULL,
    visit_date    DATE           NOT NULL,
    starts_at     DATETIME(3) NOT NULL,
    ends_at       DATETIME(3) NOT NULL,
    status        VARCHAR(24)    NOT NULL DEFAULT 'DRAFT',
    version       INT            NOT NULL DEFAULT 0,
    fee           DECIMAL(10, 2) NOT NULL,
    capacity      INT            NOT NULL,
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX         idx_schedule_doctor(doctor_id,visit_date,status,starts_at),
    INDEX         idx_schedule_room(room_id,visit_date,status,starts_at),
    FOREIGN KEY (doctor_id) REFERENCES doctor_profile (id),
    FOREIGN KEY (department_id) REFERENCES department (id),
    FOREIGN KEY (room_id) REFERENCES clinic_room (id),
    CHECK (starts_at < ends_at),
    CHECK (capacity BETWEEN 0 AND 20)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE schedule_slot
(
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    schedule_id BIGINT         NOT NULL,
    starts_at   DATETIME(3) NOT NULL,
    ends_at     DATETIME(3) NOT NULL,
    status      VARCHAR(24)    NOT NULL DEFAULT 'OPEN',
    capacity    INT            NOT NULL,
    remaining   INT            NOT NULL,
    fee         DECIMAL(10, 2) NOT NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_slot(schedule_id,starts_at),
    FOREIGN KEY (schedule_id) REFERENCES schedule (id),
    CHECK (starts_at < ends_at),
    CHECK (capacity >= 0 AND remaining BETWEEN 0 AND capacity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE appointment
(
    id                 BIGINT PRIMARY KEY AUTO_INCREMENT,
    appointment_no     VARCHAR(40)    NOT NULL UNIQUE,
    patient_id         BIGINT         NOT NULL,
    doctor_id          BIGINT         NOT NULL,
    department_id      BIGINT         NOT NULL,
    slot_id            BIGINT         NOT NULL,
    visit_date         DATE           NOT NULL,
    starts_at          DATETIME(3) NOT NULL,
    ends_at            DATETIME(3) NOT NULL,
    status             VARCHAR(24)    NOT NULL,
    registration_fee   DECIMAL(10, 2) NOT NULL,
    pay_expires_at     DATETIME(3) NOT NULL,
    cancel_deadline_at DATETIME(3) NOT NULL,
    check_in_opens_at  DATETIME(3) NOT NULL,
    checked_in_at      DATETIME(3),
    visit_started_at   DATETIME(3),
    completed_at       DATETIME(3),
    symptom_summary    VARCHAR(200),
    created_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX              idx_appt_patient(patient_id,visit_date,status),
    INDEX              idx_appt_doctor(doctor_id,visit_date,starts_at),
    INDEX              idx_appt_expiry(status,pay_expires_at),
    FOREIGN KEY (patient_id) REFERENCES patient_profile (id),
    FOREIGN KEY (doctor_id) REFERENCES doctor_profile (id),
    FOREIGN KEY (slot_id) REFERENCES schedule_slot (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE bill
(
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    bill_no        VARCHAR(40)    NOT NULL UNIQUE,
    appointment_id BIGINT         NOT NULL,
    patient_id     BIGINT         NOT NULL,
    type           VARCHAR(24)    NOT NULL,
    amount         DECIMAL(10, 2) NOT NULL,
    status         VARCHAR(24)    NOT NULL,
    paid_at        DATETIME(3),
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_bill_type(appointment_id, type),
    INDEX          idx_bill_patient(patient_id,status,created_at),
    FOREIGN KEY (appointment_id) REFERENCES appointment (id),
    FOREIGN KEY (patient_id) REFERENCES patient_profile (id),
    CHECK (amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE bill_item
(
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    bill_id     BIGINT         NOT NULL,
    item_code   VARCHAR(40)    NOT NULL,
    item_name   VARCHAR(100)   NOT NULL,
    quantity    INT            NOT NULL,
    unit_price  DECIMAL(10, 2) NOT NULL,
    line_amount DECIMAL(10, 2) NOT NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY (bill_id) REFERENCES bill (id),
    CHECK (quantity > 0 AND line_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE payment_record
(
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    bill_id     BIGINT         NOT NULL UNIQUE,
    serial_no   VARCHAR(40)    NOT NULL UNIQUE,
    amount      DECIMAL(10, 2) NOT NULL,
    method      VARCHAR(24)    NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY (bill_id) REFERENCES bill (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE refund_record
(
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    bill_id     BIGINT         NOT NULL UNIQUE,
    payment_id  BIGINT         NOT NULL,
    serial_no   VARCHAR(40)    NOT NULL UNIQUE,
    amount      DECIMAL(10, 2) NOT NULL,
    reason      VARCHAR(200),
    occurred_at DATETIME(3) NOT NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY (bill_id) REFERENCES bill (id),
    FOREIGN KEY (payment_id) REFERENCES payment_record (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE medical_record
(
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    appointment_id  BIGINT NOT NULL UNIQUE,
    doctor_id       BIGINT NOT NULL,
    chief_complaint TEXT   NOT NULL,
    examination     TEXT   NOT NULL,
    assessment      TEXT,
    treatment_note  TEXT,
    advice          TEXT   NOT NULL,
    follow_up_date  DATE,
    published_at    DATETIME(3) NOT NULL,
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY (appointment_id) REFERENCES appointment (id),
    FOREIGN KEY (doctor_id) REFERENCES doctor_profile (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE treatment_item
(
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    code       VARCHAR(40)    NOT NULL UNIQUE,
    name       VARCHAR(100)   NOT NULL,
    fee        DECIMAL(10, 2) NOT NULL,
    enabled    BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CHECK (fee >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE appointment_status_log
(
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    appointment_id BIGINT      NOT NULL,
    event_type     VARCHAR(40) NOT NULL,
    from_status    VARCHAR(24),
    to_status      VARCHAR(24) NOT NULL,
    operator_id    BIGINT,
    reason         VARCHAR(200),
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX          idx_log_appt(appointment_id,id),
    FOREIGN KEY (appointment_id) REFERENCES appointment (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE idempotency_request
(
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    actor_id     BIGINT       NOT NULL,
    operation    VARCHAR(40)  NOT NULL,
    request_key  VARCHAR(100) NOT NULL,
    request_hash CHAR(64)     NOT NULL,
    result_id    BIGINT,
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_idem(actor_id,operation,request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
