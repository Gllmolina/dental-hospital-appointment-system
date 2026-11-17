ALTER TABLE medical_record
    MODIFY chief_complaint TEXT NULL,
    MODIFY examination TEXT NULL,
    MODIFY advice TEXT NULL,
    MODIFY published_at DATETIME(3) NULL,
    ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'PUBLISHED',
    ADD COLUMN version INT NOT NULL DEFAULT 1,
    ADD COLUMN draft_items_json JSON NULL,
    ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3);

CREATE TABLE medical_record_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    record_id BIGINT NOT NULL,
    version INT NOT NULL,
    chief_complaint TEXT,
    examination TEXT,
    assessment TEXT,
    treatment_note TEXT,
    advice TEXT,
    follow_up_date DATE,
    changed_by BIGINT NOT NULL,
    reason VARCHAR(500),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_record_version (record_id, version),
    FOREIGN KEY (record_id) REFERENCES medical_record(id),
    FOREIGN KEY (changed_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO medical_record_version(record_id,version,chief_complaint,examination,assessment,treatment_note,advice,follow_up_date,changed_by,reason)
SELECT r.id,1,r.chief_complaint,r.examination,r.assessment,r.treatment_note,r.advice,r.follow_up_date,d.user_id,'历史发布记录'
FROM medical_record r JOIN doctor_profile d ON d.id=r.doctor_id;
