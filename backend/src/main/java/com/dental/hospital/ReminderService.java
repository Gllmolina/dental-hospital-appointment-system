package com.dental.hospital;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Component
public class ReminderService {
    private static final ZoneId HOSPITAL = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;

    public ReminderService(JdbcTemplate db, TransactionTemplate transactions) {
        this.db = db;
        this.transactions = transactions;
    }

    @Scheduled(cron = "0 0 18 * * *", zone = "Asia/Shanghai")
    public void scheduled() {
        runFor(LocalDate.now(HOSPITAL).plusDays(1));
    }

    public void runFor(LocalDate day) {
        for (Long id : db.queryForList("SELECT id FROM appointment WHERE visit_date=? AND status='BOOKED' ORDER BY id", Long.class, day))
            transactions.executeWithoutResult(status -> remindAppointment(id, day));
        for (Long id : db.queryForList("SELECT id FROM medical_record WHERE follow_up_date=? ORDER BY id", Long.class, day))
            transactions.executeWithoutResult(status -> remindFollowUp(id, day));
    }

    private LocalDate date(Object value) {
        return value instanceof LocalDate day ? day : ((java.sql.Date) value).toLocalDate();
    }

    public void remindAppointment(long id, LocalDate day) {
        List<Map<String, Object>> found = db.queryForList("SELECT a.id,a.patient_id,a.visit_date,a.status,p.user_id FROM appointment a JOIN patient_profile p ON p.id=a.patient_id WHERE a.id=? FOR UPDATE", id);
        if (found.isEmpty()) return;
        Map<String, Object> a = found.get(0);
        if (!"BOOKED".equals(a.get("status")) || !day.equals(date(a.get("visit_date")))) return;
        Long event = db.queryForObject("SELECT COALESCE(MAX(id),0) FROM appointment_status_log WHERE appointment_id=? AND event_type='RESCHEDULE'", Long.class, id);
        String key = "VISIT:" + id + ":" + event;
        db.update("INSERT IGNORE INTO system_message(receiver_id,appointment_id,type,title,content,dedupe_key) VALUES(?,?,'VISIT_REMINDER','明日就诊提醒','请按预约时间到院就诊。',?)", a.get("user_id"), id, key);
    }

    public void remindFollowUp(long id, LocalDate day) {
        List<Map<String, Object>> found = db.queryForList("SELECT r.id,r.follow_up_date,a.patient_id,p.user_id FROM medical_record r JOIN appointment a ON a.id=r.appointment_id JOIN patient_profile p ON p.id=a.patient_id WHERE r.id=? FOR UPDATE", id);
        if (found.isEmpty() || !day.equals(date(found.get(0).get("follow_up_date")))) return;
        Map<String, Object> record = found.get(0);
        String key = "FOLLOW_UP:" + id + ":" + day;
        db.update("INSERT IGNORE INTO system_message(receiver_id,appointment_id,type,title,content,dedupe_key) VALUES(?,NULL,'FOLLOW_UP_REMINDER','复诊提醒','建议按医嘱安排复诊。',?)", record.get("user_id"), key);
    }
}
