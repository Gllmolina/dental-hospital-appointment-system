package com.dental.hospital;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class AdminApi {
    private final JdbcTemplate db;
    private final Api api;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public AdminApi(JdbcTemplate db, Api api) {
        this.db = db;
        this.api = api;
    }

    private Api.Actor admin(HttpServletRequest req) {
        return api.actor(req, "ADMIN");
    }

    private Map<String, Object> row(String sql, Object... args) {
        List<Map<String, Object>> result = db.queryForList(sql, args);
        if (result.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
        return result.get(0);
    }

    private long count(String sql, Object... args) {
        Long n = db.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private String value(Map<String, Object> m, String key) {
        return Objects.toString(m.get(key), "").trim();
    }

    private String required(Map<String, Object> m, String key) {
        String v = value(m, key);
        if (v.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + "不能为空");
        return v;
    }

    private void valid(boolean ok, String message) {
        if (!ok) throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private boolean bool(Map<String, Object> m, String key, boolean current) {
        return m.containsKey(key) ? Boolean.parseBoolean(value(m, key)) : current;
    }

    private boolean bool(Object o) {
        return o instanceof Boolean b ? b : ((Number) o).intValue() != 0;
    }

    private void audit(Api.Actor a, String action, String type, long target, String reason) {
        db.update("INSERT INTO admin_operation_log(operator_id,action,target_type,target_id,reason) VALUES(?,?,?,?,?)", a.id(), action, type, target, reason);
    }

    private LocalDateTime utc(String date, String time) {
        return LocalDateTime.parse(date + "T" + time).atZone(ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    @GetMapping("/users")
    public Map<String, Object> users(HttpServletRequest req, @RequestParam(required = false) String role, @RequestParam(required = false) String status) {
        admin(req);
        String sql = "SELECT id,username,phone,role,status,must_change_password,created_at FROM sys_user WHERE 1=1";
        List<Object> args = new ArrayList<>();
        if (role != null && !role.isBlank()) {
            sql += " AND role=?";
            args.add(role);
        }
        if (status != null && !status.isBlank()) {
            sql += " AND status=?";
            args.add(status);
        }
        return Map.of("code", 0, "items", db.queryForList(sql + " ORDER BY id DESC LIMIT 100", args.toArray()));
    }

    @PutMapping("/users/{id}/status")
    @Transactional
    public Map<String, Object> userStatus(HttpServletRequest req, @PathVariable long id, @RequestBody Map<String, Object> m) {
        Api.Actor a = admin(req);
        Map<String, Object> u = row("SELECT id,role,status FROM sys_user WHERE id=? FOR UPDATE", id);
        String status = required(m, "status");
        valid(status.equals("ACTIVE") || status.equals("DISABLED"), "账号状态错误");
        valid(id != a.id() || status.equals("ACTIVE"), "不能停用当前管理员");
        if ("DOCTOR".equals(u.get("role")) && status.equals("DISABLED"))
            valid(count("SELECT COUNT(*) FROM schedule s JOIN doctor_profile d ON d.id=s.doctor_id WHERE d.user_id=? AND s.status='PUBLISHED' AND s.ends_at>UTC_TIMESTAMP(3)", id) == 0, "医生还有未来已发布排班");
        db.update("UPDATE sys_user SET status=? WHERE id=?", status, id);
        if (status.equals("DISABLED"))
            db.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP(3) WHERE user_id=? AND revoked_at IS NULL", id);
        audit(a, "USER_STATUS", "USER", id, value(m, "reason"));
        return Map.of("code", 0, "id", Long.toString(id), "status", status);
    }

    @PostMapping("/users/{id}/reset-password")
    @Transactional
    public Map<String, Object> resetPassword(HttpServletRequest req, @PathVariable long id, @RequestBody Map<String, Object> m) {
        Api.Actor a = admin(req);
        row("SELECT id FROM sys_user WHERE id=? FOR UPDATE", id);
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$";
        SecureRandom random = new SecureRandom();
        StringBuilder pw = new StringBuilder();
        for (int i = 0; i < 16; i++) pw.append(alphabet.charAt(random.nextInt(alphabet.length())));
        db.update("UPDATE sys_user SET password_hash=?,must_change_password=TRUE WHERE id=?", bcrypt.encode(pw.toString()), id);
        db.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP(3) WHERE user_id=? AND revoked_at IS NULL", id);
        audit(a, "RESET_PASSWORD", "USER", id, value(m, "reason"));
        return Map.of("code", 0, "temporaryPassword", pw.toString());
    }

    @PutMapping("/departments/{id}")
    @Transactional
    public Map<String, Object> department(HttpServletRequest req, @PathVariable long id, @RequestBody Map<String, Object> m) {
        Api.Actor a = admin(req);
        Map<String, Object> d = row("SELECT * FROM department WHERE id=? FOR UPDATE", id);
        boolean enabled = bool(m, "enabled", bool(d.get("enabled")));
        if (!enabled)
            valid(count("SELECT COUNT(*) FROM schedule WHERE department_id=? AND status='PUBLISHED' AND ends_at>UTC_TIMESTAMP(3)", id) == 0, "科室还有未来已发布排班");
        db.update("UPDATE department SET name=?,description=?,sort_order=?,enabled=? WHERE id=?", m.getOrDefault("name", d.get("name")), m.getOrDefault("description", d.get("description")), m.getOrDefault("sortOrder", d.get("sort_order")), enabled, id);
        audit(a, "DEPARTMENT_UPDATE", "DEPARTMENT", id, null);
        return Map.of("code", 0, "id", Long.toString(id));
    }

    @PutMapping("/rooms/{id}")
    @Transactional
    public Map<String, Object> room(HttpServletRequest req, @PathVariable long id, @RequestBody Map<String, Object> m) {
        Api.Actor a = admin(req);
        Map<String, Object> r = row("SELECT * FROM clinic_room WHERE id=? FOR UPDATE", id);
        boolean enabled = bool(m, "enabled", bool(r.get("enabled")));
        if (!enabled)
            valid(count("SELECT COUNT(*) FROM schedule WHERE room_id=? AND status='PUBLISHED' AND ends_at>UTC_TIMESTAMP(3)", id) == 0, "诊室还有未来已发布排班");
        db.update("UPDATE clinic_room SET name=?,enabled=? WHERE id=?", m.getOrDefault("name", r.get("name")), enabled, id);
        audit(a, "ROOM_UPDATE", "ROOM", id, null);
        return Map.of("code", 0, "id", Long.toString(id));
    }

    @GetMapping("/doctors")
    public Map<String, Object> doctors(HttpServletRequest req) {
        admin(req);
        return Map.of("code", 0, "items", db.queryForList("SELECT d.id,d.user_id,d.employee_no,d.department_id,d.name,d.title,d.specialty,d.intro,d.default_fee,d.status,u.username,u.phone FROM doctor_profile d JOIN sys_user u ON u.id=d.user_id ORDER BY d.id DESC LIMIT 100"));
    }

    @PutMapping("/doctors/{id}")
    @Transactional
    public Map<String, Object> doctor(HttpServletRequest req, @PathVariable long id, @RequestBody Map<String, Object> m) {
        Api.Actor a = admin(req);
        Map<String, Object> d = row("SELECT * FROM doctor_profile WHERE id=? FOR UPDATE", id);
        String status = m.containsKey("status") ? required(m, "status") : value(d, "status");
        valid(status.equals("ACTIVE") || status.equals("DISABLED"), "医生状态错误");
        if (status.equals("DISABLED"))
            valid(count("SELECT COUNT(*) FROM schedule WHERE doctor_id=? AND status='PUBLISHED' AND ends_at>UTC_TIMESTAMP(3)", id) == 0, "医生还有未来已发布排班");
        long department = m.containsKey("departmentId") ? Long.parseLong(required(m, "departmentId")) : ((Number) d.get("department_id")).longValue();
        row("SELECT id FROM department WHERE id=? AND enabled=TRUE", department);
        BigDecimal fee = m.containsKey("defaultFee") ? new BigDecimal(required(m, "defaultFee")) : (BigDecimal) d.get("default_fee");
        valid(fee.signum() > 0, "挂号费必须为正");
        db.update("UPDATE doctor_profile SET department_id=?,name=?,title=?,specialty=?,intro=?,default_fee=?,status=? WHERE id=?", department, m.getOrDefault("name", d.get("name")), m.getOrDefault("title", d.get("title")), m.getOrDefault("specialty", d.get("specialty")), m.getOrDefault("intro", d.get("intro")), fee, status, id);
        audit(a, "DOCTOR_UPDATE", "DOCTOR", id, null);
        return Map.of("code", 0, "id", Long.toString(id), "status", status);
    }

    @PutMapping("/schedules/{id}")
    @Transactional
    public Map<String, Object> schedule(HttpServletRequest req, @PathVariable long id, @RequestBody Map<String, Object> m) {
        Api.Actor a = admin(req);
        Map<String, Object> s = row("SELECT * FROM schedule WHERE id=? FOR UPDATE", id);
        valid("DRAFT".equals(s.get("status")), "只有草稿可以编辑");
        valid(((Number) s.get("version")).longValue() == Long.parseLong(required(m, "version")), "版本过期");
        long doctor = Long.parseLong(required(m, "doctorId")), room = Long.parseLong(required(m, "roomId"));
        Map<String, Object> d = row("SELECT department_id FROM doctor_profile WHERE id=? AND status='ACTIVE'", doctor);
        row("SELECT id FROM clinic_room WHERE id=? AND enabled=TRUE", room);
        LocalDate date = LocalDate.parse(required(m, "visitDate"));
        LocalDateTime start = utc(date.toString(), required(m, "startTime")), end = utc(date.toString(), required(m, "endTime"));
        valid(start.isBefore(end) && start.isAfter(LocalDateTime.now(ZoneOffset.UTC)), "排班时间错误");
        int capacity = Integer.parseInt(required(m, "capacity"));
        valid(capacity >= 0 && capacity <= 20, "容量必须在0到20之间");
        BigDecimal fee = new BigDecimal(required(m, "fee"));
        valid(fee.signum() > 0, "挂号费必须为正");
        db.update("UPDATE schedule SET doctor_id=?,department_id=?,room_id=?,visit_date=?,starts_at=?,ends_at=?,fee=?,capacity=?,version=version+1 WHERE id=?", doctor, d.get("department_id"), room, date, start, end, fee, capacity, id);
        audit(a, "SCHEDULE_EDIT", "SCHEDULE", id, null);
        return Map.of("code", 0, "id", Long.toString(id), "version", ((Number) s.get("version")).longValue() + 1);
    }

    @PutMapping("/slots/{id}/capacity")
    @Transactional
    public Map<String, Object> capacity(HttpServletRequest req, @PathVariable long id, @RequestBody Map<String, Object> m) {
        Api.Actor a = admin(req);
        Map<String, Object> link = row("SELECT schedule_id FROM schedule_slot WHERE id=?", id);
        Map<String, Object> s = row("SELECT status FROM schedule WHERE id=? FOR SHARE", link.get("schedule_id"));
        valid("PUBLISHED".equals(s.get("status")), "排班未发布");
        Map<String, Object> sl = row("SELECT * FROM schedule_slot WHERE id=? FOR UPDATE", id);
        valid((sl.get("starts_at") instanceof LocalDateTime dt ? dt : ((java.sql.Timestamp) sl.get("starts_at")).toLocalDateTime()).isAfter(LocalDateTime.now(ZoneOffset.UTC)), "号源已开始");
        int requested = Integer.parseInt(required(m, "capacity"));
        int used = ((Number) sl.get("capacity")).intValue() - ((Number) sl.get("remaining")).intValue();
        valid(requested >= used && requested <= 20, "新容量小于已占用数或超过20");
        db.update("UPDATE schedule_slot SET capacity=?,remaining=? WHERE id=?", requested, requested - used, id);
        audit(a, "SLOT_CAPACITY", "SLOT", id, null);
        return Map.of("code", 0, "capacity", requested, "remaining", requested - used);
    }

    @GetMapping("/appointments")
    public Map<String, Object> appointments(HttpServletRequest req, @RequestParam(required = false) String visitDate) {
        admin(req);
        return Map.of("code", 0, "items", visitDate == null ? db.queryForList("SELECT id,appointment_no,patient_id,doctor_id,visit_date,starts_at,status FROM appointment ORDER BY id DESC LIMIT 100") : db.queryForList("SELECT id,appointment_no,patient_id,doctor_id,visit_date,starts_at,status FROM appointment WHERE visit_date=? ORDER BY starts_at LIMIT 100", LocalDate.parse(visitDate)));
    }

    @GetMapping("/appointments/{id}/events")
    public Map<String, Object> events(HttpServletRequest req, @PathVariable long id) {
        admin(req);
        row("SELECT id FROM appointment WHERE id=?", id);
        return Map.of("code", 0, "items", db.queryForList("SELECT event_type,from_status,to_status,operator_id,reason,created_at FROM appointment_status_log WHERE appointment_id=? ORDER BY id", id));
    }

    @GetMapping("/bills")
    public Map<String, Object> bills(HttpServletRequest req) {
        admin(req);
        return Map.of("code", 0, "items", db.queryForList("SELECT id,bill_no,appointment_id,patient_id,type,amount,status,created_at FROM bill ORDER BY id DESC LIMIT 100"));
    }

    @PutMapping("/treatment-items/{id}")
    @Transactional
    public Map<String, Object> treatmentItem(HttpServletRequest req, @PathVariable long id, @RequestBody Map<String, Object> m) {
        Api.Actor a = admin(req);
        Map<String, Object> item = row("SELECT * FROM treatment_item WHERE id=? FOR UPDATE", id);
        BigDecimal fee = m.containsKey("fee") ? new BigDecimal(required(m, "fee")) : (BigDecimal) item.get("fee");
        valid(fee.signum() >= 0, "价格不能为负");
        db.update("UPDATE treatment_item SET name=?,fee=?,enabled=? WHERE id=?", m.getOrDefault("name", item.get("name")), fee, bool(m, "enabled", bool(item.get("enabled"))), id);
        audit(a, "TREATMENT_ITEM_UPDATE", "TREATMENT_ITEM", id, null);
        return Map.of("code", 0, "id", Long.toString(id));
    }

    @GetMapping("/audit-logs")
    public Map<String, Object> auditLogs(HttpServletRequest req) {
        admin(req);
        return Map.of("code", 0, "items", db.queryForList("SELECT id,operator_id,action,target_type,target_id,reason,created_at FROM admin_operation_log ORDER BY id DESC LIMIT 100"));
    }
}
