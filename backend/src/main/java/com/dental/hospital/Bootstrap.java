package com.dental.hospital;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Component
public class Bootstrap implements CommandLineRunner {
    private final JdbcTemplate db;
    @Value("${ADMIN_USERNAME:}")
    private String username;
    @Value("${ADMIN_PHONE:}")
    private String phone;
    @Value("${ADMIN_PASSWORD:}")
    private String password;

    public Bootstrap(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public void run(String... args) {
        Long count = db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE role='ADMIN'", Long.class);
        if (count != null && count == 0) {
            if (username.isBlank() || phone.isBlank() || password.length() < 8)
                throw new IllegalStateException("首次启动请设置 ADMIN_USERNAME、ADMIN_PHONE、ADMIN_PASSWORD（至少8位）");
            db.update("INSERT INTO sys_user(username,phone,password_hash,role,must_change_password) VALUES(?,?,?,'ADMIN',TRUE)", username.toLowerCase(), phone, new BCryptPasswordEncoder().encode(password));
        }
    }

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void expire() {
        List<Map<String, Object>> due = db.queryForList("SELECT id,slot_id FROM appointment WHERE status='PENDING_PAYMENT' AND pay_expires_at<=UTC_TIMESTAMP(3) ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED");
        for (Map<String, Object> a : due) {
            long id = ((Number) a.get("id")).longValue();
            if (db.update("UPDATE appointment SET status='EXPIRED' WHERE id=? AND status='PENDING_PAYMENT'", id) == 1) {
                db.update("UPDATE schedule_slot SET remaining=remaining+1 WHERE id=?", a.get("slot_id"));
                db.update("UPDATE bill SET status='CLOSED' WHERE appointment_id=? AND type='REGISTRATION' AND status='UNPAID'", id);
                db.update("INSERT INTO appointment_status_log(appointment_id,event_type,from_status,to_status) VALUES(?,'EXPIRE','PENDING_PAYMENT','EXPIRED')", id);
            }
        }
    }
}
