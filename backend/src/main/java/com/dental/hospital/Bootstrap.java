package com.dental.hospital;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class Bootstrap implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);
    private final JdbcTemplate db;
    private final Api api;
    @Value("${ADMIN_USERNAME:}")
    private String username;
    @Value("${ADMIN_PHONE:}")
    private String phone;
    @Value("${ADMIN_PASSWORD:}")
    private String password;

    public Bootstrap(JdbcTemplate db, Api api) {
        this.db = db;
        this.api = api;
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
    public void expire() {
        for (long appointmentId : db.queryForList("""
                SELECT id FROM appointment WHERE status='PENDING_PAYMENT' AND pay_expires_at<=UTC_TIMESTAMP(3)
                ORDER BY pay_expires_at,id LIMIT 100
                """, Long.class)) {
            api.expireAppointment(appointmentId);
        }
    }

    @Scheduled(fixedDelayString = "${booking.disruption-scan-delay-ms:5000}")
    public void scanDisruptions() {
        for (long appointmentId : db.queryForList("""
                SELECT a.id FROM schedule s JOIN schedule_slot sl ON sl.schedule_id=s.id
                JOIN appointment a ON a.slot_id=sl.id
                LEFT JOIN appointment_disruption d ON d.appointment_id=a.id AND d.suspended_schedule_id=s.id
                WHERE s.status='SUSPENDED' AND a.status IN ('PENDING_PAYMENT','BOOKED') AND d.id IS NULL
                ORDER BY a.id LIMIT 100
                """, Long.class)) {
            try {
                api.processDisruption(appointmentId);
            } catch (RuntimeException ex) {
                log.warn("Unable to process disruption for appointment {}", appointmentId, ex);
            }
        }
    }
}
