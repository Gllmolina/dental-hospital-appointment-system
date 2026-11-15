package com.dental.hospital;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class Api {
    private final JdbcTemplate db;
    private final JwtTokens jwt;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private static final ZoneId HOSPITAL = ZoneId.of("Asia/Shanghai");

    public Api(JdbcTemplate db, JwtTokens jwt) {
        this.db = db;
        this.jwt = jwt;
    }

    record Actor(long id, String role, long profileId) {
    }

    private long id(String sql, Object... args) {
        Long n = db.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private Map<String, Object> row(String sql, Object... args) {
        List<Map<String, Object>> rows = db.queryForList(sql, args);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
        return rows.get(0);
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        return db.queryForList(sql, args);
    }

    private long num(Map<String, Object> m, String k) {
        return ((Number) m.get(k)).longValue();
    }

    private String str(Map<String, Object> m, String k) {
        return Objects.toString(m.get(k), "");
    }

    private String required(Map<String, Object> m, String k) {
        String v = str(m, k).trim();
        if (v.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, k + "不能为空");
        return v;
    }

    private void check(boolean ok, String why) {
        if (!ok) throw new ResponseStatusException(HttpStatus.CONFLICT, why);
    }

    private String hash(String input) {
        try {
            byte[] b = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(b);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long insert(String sql, Object... values) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        db.update(c -> {
            PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
            return ps;
        }, kh);
        return Objects.requireNonNull(kh.getKey()).longValue();
    }

    Actor actor(HttpServletRequest req, String role) {
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer "))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请登录");
        String raw = auth.substring(7);
        JwtTokens.Claims claims;
        try { claims = jwt.verify(raw); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效"); }
        List<Map<String, Object>> a = rows("SELECT u.id,u.role,u.status,u.must_change_password FROM auth_session s JOIN sys_user u ON u.id=s.user_id WHERE s.jti=? AND s.user_id=? AND s.token_hash=? AND s.revoked_at IS NULL AND s.expires_at>UTC_TIMESTAMP(3)", claims.jti(), claims.userId(), hash(raw));
        if (a.isEmpty() || !"ACTIVE".equals(a.get(0).get("status")))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        long user = num(a.get(0), "id");
        String r = str(a.get(0), "role");
        boolean mustChange = Boolean.TRUE.equals(a.get(0).get("must_change_password")) || a.get(0).get("must_change_password") instanceof Number n && n.intValue()!=0;
        if (mustChange && !req.getRequestURI().matches(".*/api/auth/(me|logout|password)$"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"请先修改初始密码");
        if (role != null && !role.equals(r)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问");
        long profile = "PATIENT".equals(r) ? id("SELECT id FROM patient_profile WHERE user_id=?", user) : "DOCTOR".equals(r) ? id("SELECT id FROM doctor_profile WHERE user_id=?", user) : 0;
        return new Actor(user, r, profile);
    }

    private long idem(Actor a, String operation, String key, String payload) {
        if (key == null || key.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 Idempotency-Key");
        String digest = hash(payload);
        try {
            db.update("INSERT INTO idempotency_request(actor_id,operation,request_key,request_hash) VALUES(?,?,?,?)", a.id, operation, key, digest);
            return 0;
        } catch (DuplicateKeyException ex) {
            Map<String, Object> old = row("SELECT request_hash,result_id FROM idempotency_request WHERE actor_id=? AND operation=? AND request_key=? FOR UPDATE", a.id, operation, key);
            check(digest.equals(old.get("request_hash")), "幂等键与请求内容冲突");
            return old.get("result_id") == null ? -1 : num(old, "result_id");
        }
    }

    private void idemDone(Actor a, String op, String key, long result) {
        db.update("UPDATE idempotency_request SET result_id=? WHERE actor_id=? AND operation=? AND request_key=?", result, a.id, op, key);
    }

    private void event(long appt, String type, String from, String to, Long operator, String reason) {
        db.update("INSERT INTO appointment_status_log(appointment_id,event_type,from_status,to_status,operator_id,reason) VALUES(?,?,?,?,?,?)", appt, type, from, to, operator, reason);
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private LocalDateTime dateTime(Object value) {
        return value instanceof LocalDateTime x ? x : ((java.sql.Timestamp) value).toLocalDateTime();
    }

    private LocalDate date(Object value) {
        return value instanceof LocalDate x ? x : ((java.sql.Date) value).toLocalDate();
    }

    private LocalDateTime utc(String local) {
        return LocalDateTime.parse(local).atZone(HOSPITAL).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    @PostMapping("/auth/register")
    @Transactional
    public Map<String, Object> register(@RequestBody Map<String, Object> m) {
        String username = required(m, "username").toLowerCase(Locale.ROOT), phone = required(m, "phone"), password = required(m, "password"), name = required(m, "name");
        if (!username.matches("[a-z0-9_]{4,32}") || !phone.matches("1[0-9]{10}") || password.length() < 8 || password.length() > 64 || password.getBytes(StandardCharsets.UTF_8).length > 72 || name.length() > 30)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "注册字段格式错误");
        long user = insert("INSERT INTO sys_user(username,phone,password_hash,role) VALUES(?,?,?,'PATIENT')", username, phone, bcrypt.encode(password));
        insert("INSERT INTO patient_profile(user_id,name,gender,birthday) VALUES(?,?,?,?)", user, name, m.get("gender"), m.get("birthday"));
        return Map.of("code", 0, "userId", Long.toString(user));
    }

    @PostMapping("/auth/login")
    @Transactional
    public Map<String, Object> login(@RequestBody Map<String, Object> m) {
        List<Map<String, Object>> found = rows("SELECT id,password_hash,role,status,must_change_password FROM sys_user WHERE username=? OR phone=?", required(m, "identifier"), str(m, "identifier"));
        if (found.isEmpty() || !bcrypt.matches(required(m, "password"), str(found.get(0), "password_hash")) || !"ACTIVE".equals(found.get(0).get("status")))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        Map<String, Object> u = found.get(0);
        String jti = UUID.randomUUID().toString();
        LocalDateTime expiry = utcNow().plusMinutes(60);
        String token = jwt.issue(num(u, "id"), jti, java.time.Instant.now(), expiry.toInstant(ZoneOffset.UTC));
        db.update("INSERT INTO auth_session(user_id,jti,token_hash,expires_at) VALUES(?,?,?,?)", num(u, "id"), jti, hash(token), expiry);
        return Map.of("code", 0, "accessToken", token, "expiresAt", expiry.atOffset(ZoneOffset.UTC).toString(), "role", u.get("role"), "mustChangePassword", u.get("must_change_password"));
    }

    @GetMapping("/auth/me")
    public Map<String, Object> me(HttpServletRequest req) {
        Actor a = actor(req, null);
        return Map.of("code", 0, "user", row("SELECT id,username,phone,role,status,must_change_password FROM sys_user WHERE id=?", a.id));
    }

    @PostMapping("/auth/logout")
    public Map<String, Object> logout(HttpServletRequest req) {
        actor(req, null);
        db.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP(3) WHERE token_hash=?", hash(req.getHeader("Authorization").substring(7)));
        return Map.of("code", 0);
    }

    @PutMapping("/auth/password")
    @Transactional
    public Map<String, Object> password(HttpServletRequest req, @RequestBody Map<String, Object> m) {
        Actor a = actor(req, null);
        Map<String, Object> u = row("SELECT password_hash FROM sys_user WHERE id=? FOR UPDATE", a.id);
        String next = required(m, "newPassword");
        if (!bcrypt.matches(required(m, "oldPassword"), str(u, "password_hash")) || next.length() < 8 || next.length() > 64 || next.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码不符合要求");
        db.update("UPDATE sys_user SET password_hash=?,must_change_password=FALSE WHERE id=?", bcrypt.encode(next), a.id);
        db.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP(3) WHERE user_id=? AND revoked_at IS NULL", a.id);
        return Map.of("code", 0);
    }

    @GetMapping("/departments")
    public Map<String, Object> departments() {
        return Map.of("code", 0, "items", rows("SELECT id,code,name,description FROM department WHERE enabled=TRUE ORDER BY sort_order,id"));
    }

    @GetMapping("/doctors")
    public Map<String, Object> doctors(@RequestParam(required = false) Long departmentId) {
        return Map.of("code", 0, "items", departmentId == null ? rows("SELECT d.id,d.name,d.title,d.specialty,d.intro,d.default_fee,dep.name department_name FROM doctor_profile d JOIN department dep ON dep.id=d.department_id JOIN sys_user u ON u.id=d.user_id WHERE d.status='ACTIVE' AND u.status='ACTIVE'") : rows("SELECT d.id,d.name,d.title,d.specialty,d.intro,d.default_fee,dep.name department_name FROM doctor_profile d JOIN department dep ON dep.id=d.department_id JOIN sys_user u ON u.id=d.user_id WHERE d.status='ACTIVE' AND u.status='ACTIVE' AND d.department_id=?", departmentId));
    }

    @GetMapping("/doctors/{id}/slots")
    public Map<String, Object> slots(@PathVariable long id) {
        return Map.of("code", 0, "items", rows("SELECT sl.id slot_id,sl.starts_at,sl.ends_at,sl.remaining,sl.fee,r.name room_name FROM schedule_slot sl JOIN schedule s ON s.id=sl.schedule_id JOIN clinic_room r ON r.id=s.room_id WHERE s.doctor_id=? AND s.status='PUBLISHED' AND sl.status='OPEN' AND sl.remaining>0 AND sl.starts_at>UTC_TIMESTAMP(3) AND s.visit_date BETWEEN ? AND ? ORDER BY sl.starts_at", id, LocalDate.now(HOSPITAL), LocalDate.now(HOSPITAL).plusDays(6)));
    }

    @GetMapping("/admin/departments")
    public Map<String, Object> adminDepartments(HttpServletRequest req) {
        actor(req, "ADMIN");
        return Map.of("code", 0, "items", rows("SELECT * FROM department ORDER BY sort_order,id"));
    }

    @PostMapping("/admin/departments")
    @Transactional
    public Map<String, Object> addDepartment(HttpServletRequest req, @RequestBody Map<String, Object> m) {
        actor(req, "ADMIN");
        long id = insert("INSERT INTO department(code,name,description,sort_order) VALUES(?,?,?,?)", required(m, "code"), required(m, "name"), m.get("description"), m.getOrDefault("sortOrder", 0));
        return Map.of("code", 0, "id", Long.toString(id));
    }

    @GetMapping("/admin/rooms")
    public Map<String, Object> rooms(HttpServletRequest req) {
        actor(req, "ADMIN");
        return Map.of("code", 0, "items", rows("SELECT * FROM clinic_room"));
    }

    @PostMapping("/admin/rooms")
    @Transactional
    public Map<String, Object> addRoom(HttpServletRequest req, @RequestBody Map<String, Object> m) {
        actor(req, "ADMIN");
        long id = insert("INSERT INTO clinic_room(code,name) VALUES(?,?)", required(m, "code"), required(m, "name"));
        return Map.of("code", 0, "id", Long.toString(id));
    }

    @PostMapping("/admin/doctors")
    @Transactional
    public Map<String, Object> addDoctor(HttpServletRequest req, @RequestBody Map<String, Object> m) {
        actor(req, "ADMIN");
        long dep = Long.parseLong(required(m, "departmentId"));
        row("SELECT id FROM department WHERE id=? AND enabled=TRUE", dep);
        long user = insert("INSERT INTO sys_user(username,phone,password_hash,role,must_change_password) VALUES(?,?,?,'DOCTOR',TRUE)", required(m, "username").toLowerCase(Locale.ROOT), required(m, "phone"), bcrypt.encode(required(m, "password")));
        long id = insert("INSERT INTO doctor_profile(user_id,employee_no,department_id,name,title,specialty,intro,default_fee) VALUES(?,?,?,?,?,?,?,?)", user, required(m, "employeeNo"), dep, required(m, "name"), m.get("title"), m.get("specialty"), m.get("intro"), new BigDecimal(required(m, "defaultFee")));
        return Map.of("code", 0, "id", Long.toString(id));
    }

    @GetMapping("/admin/schedules")
    public Map<String, Object> schedules(HttpServletRequest req) {
        actor(req, "ADMIN");
        return Map.of("code", 0, "items", rows("SELECT * FROM schedule ORDER BY visit_date DESC,id DESC LIMIT 100"));
    }

    @GetMapping("/admin/treatment-items")
    public Map<String, Object> treatmentItems(HttpServletRequest req) {
        actor(req, "ADMIN");
        return Map.of("code", 0, "items", rows("SELECT * FROM treatment_item ORDER BY id"));
    }

    @PostMapping("/admin/treatment-items")
    @Transactional
    public Map<String, Object> addTreatmentItem(HttpServletRequest req, @RequestBody Map<String, Object> m) {
        actor(req, "ADMIN");
        BigDecimal fee = new BigDecimal(required(m, "fee"));
        check(fee.signum() >= 0, "价格不能为负");
        long id = insert("INSERT INTO treatment_item(code,name,fee) VALUES(?,?,?)", required(m, "code"), required(m, "name"), fee);
        return Map.of("code", 0, "id", Long.toString(id));
    }

    @PostMapping("/admin/schedules")
    @Transactional
    public Map<String, Object> draft(HttpServletRequest req, @RequestBody Map<String, Object> m) {
        actor(req, "ADMIN");
        long doctor = Long.parseLong(required(m, "doctorId")), room = Long.parseLong(required(m, "roomId"));
        Map<String, Object> d = row("SELECT department_id,default_fee FROM doctor_profile WHERE id=? AND status='ACTIVE'", doctor);
        row("SELECT id FROM clinic_room WHERE id=? AND enabled=TRUE", room);
        LocalDate date = LocalDate.parse(required(m, "visitDate"));
        LocalDateTime start = utc(date + "T" + required(m, "startTime")), end = utc(date + "T" + required(m, "endTime"));
        check(start.isBefore(end), "时间区间错误");
        int capacity = Integer.parseInt(required(m, "capacity"));
        check(capacity >= 0 && capacity <= 20, "容量必须在0到20之间");
        long id = insert("INSERT INTO schedule(doctor_id,department_id,room_id,visit_date,starts_at,ends_at,fee,capacity) VALUES(?,?,?,?,?,?,?,?)", doctor, num(d, "department_id"), room, date, start, end, new BigDecimal(required(m, "fee")), capacity);
        return Map.of("code", 0, "id", Long.toString(id));
    }

    @PutMapping("/admin/schedules/{id}/publish")
    @Transactional
    public Map<String, Object> publish(HttpServletRequest req, @PathVariable long id, @RequestBody Map<String, Object> m) {
        actor(req, "ADMIN");
        Map<String, Object> initial = row("SELECT doctor_id,room_id FROM schedule WHERE id=?", id);
        row("SELECT id FROM doctor_profile WHERE id=? FOR UPDATE", initial.get("doctor_id"));
        row("SELECT id FROM clinic_room WHERE id=? FOR UPDATE", initial.get("room_id"));
        Map<String, Object> s = row("SELECT * FROM schedule WHERE id=? FOR UPDATE", id);
        check(Objects.equals(initial.get("doctor_id"),s.get("doctor_id")) && Objects.equals(initial.get("room_id"),s.get("room_id")), "排班资源已变化");
        check("DRAFT".equals(s.get("status")), "排班不是草稿");
        check(num(s, "version") == Long.parseLong(required(m, "version")), "版本过期");
        List<Map<String,Object>> conflicts = rows("SELECT id FROM schedule WHERE id<>? AND status='PUBLISHED' AND visit_date=? AND starts_at<? AND ends_at>? AND (doctor_id=? OR room_id=?) LIMIT 1 FOR UPDATE", id, s.get("visit_date"), s.get("ends_at"), s.get("starts_at"), s.get("doctor_id"), s.get("room_id"));
        check(conflicts.isEmpty(), "医生或诊室排班冲突");
        LocalDateTime start = dateTime(s.get("starts_at")), end = dateTime(s.get("ends_at"));
        check(start.isAfter(utcNow()), "排班已开始");
        for (LocalDateTime t = start; t.isBefore(end); t = t.plusMinutes(30)) {
            LocalDateTime next = t.plusMinutes(30);
            check(!next.isAfter(end), "排班须按30分钟整分");
            db.update("INSERT INTO schedule_slot(schedule_id,starts_at,ends_at,capacity,remaining,fee) VALUES(?,?,?,?,?,?)", id, t, next, s.get("capacity"), s.get("capacity"), s.get("fee"));
        }
        db.update("UPDATE schedule SET status='PUBLISHED',version=version+1 WHERE id=?", id);
        return Map.of("code", 0, "id", Long.toString(id), "status", "PUBLISHED");
    }

    @PostMapping("/appointments")
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String, Object> book(HttpServletRequest req, @RequestHeader("Idempotency-Key") String key, @RequestBody Map<String, Object> m) {
        Actor a = actor(req, "PATIENT");
        long slot = Long.parseLong(required(m, "slotId"));
        String symptom = str(m, "symptomSummary");
        check(symptom.length() <= 200, "症状摘要过长");
        long existing = idem(a, "BOOK", key, slot + "|" + symptom);
        if (existing > 0) return appointmentResult(existing);
        check(existing == 0, "请求正在处理");
        db.queryForList("SELECT id FROM patient_profile WHERE id=? FOR UPDATE", a.profileId);
        Map<String, Object> sl = row("SELECT sl.*,s.doctor_id,s.department_id,s.visit_date,s.status schedule_status FROM schedule_slot sl JOIN schedule s ON s.id=sl.schedule_id WHERE sl.id=? FOR UPDATE", slot);
        LocalDate date = date(sl.get("visit_date"));
        LocalDateTime start = dateTime(sl.get("starts_at")), end = dateTime(sl.get("ends_at")), now = utcNow();
        check(!date.isBefore(LocalDate.now(HOSPITAL)) && date.isBefore(LocalDate.now(HOSPITAL).plusDays(7)) && start.isAfter(now), "不在预约窗口");
        check("PUBLISHED".equals(sl.get("schedule_status")) && "OPEN".equals(sl.get("status")) && num(sl, "remaining") > 0, "号源不可用");
        check(rows("SELECT id FROM appointment WHERE patient_id=? AND visit_date=? AND status NOT IN('CANCELLED','EXPIRED') FOR UPDATE", a.profileId, date).size() < 3, "超过每日预约上限");
        check(rows("SELECT id FROM appointment WHERE patient_id=? AND status NOT IN('CANCELLED','EXPIRED') AND starts_at<? AND ends_at>? LIMIT 1 FOR UPDATE", a.profileId, end, start).isEmpty(), "时间重叠");
        check(db.update("UPDATE schedule_slot SET remaining=remaining-1 WHERE id=? AND remaining>0 AND status='OPEN'", slot) == 1, "号源已满");
        String no = "A" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        LocalDateTime expiry = now.plusMinutes(15).isBefore(start) ? now.plusMinutes(15) : start;
        long appt = insert("INSERT INTO appointment(appointment_no,patient_id,doctor_id,department_id,slot_id,visit_date,starts_at,ends_at,status,registration_fee,pay_expires_at,cancel_deadline_at,check_in_opens_at,symptom_summary) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", no, a.profileId, sl.get("doctor_id"), sl.get("department_id"), slot, date, start, end, "PENDING_PAYMENT", sl.get("fee"), expiry, start.minusMinutes(120), start.minusMinutes(30), symptom);
        long bill = insert("INSERT INTO bill(bill_no,appointment_id,patient_id,type,amount,status) VALUES(?,?,?,'REGISTRATION',?,'UNPAID')", "B" + no, appt, a.profileId, sl.get("fee"));
        db.update("INSERT INTO bill_item(bill_id,item_code,item_name,quantity,unit_price,line_amount) VALUES(?,'REG','挂号费',1,?,?)", bill, sl.get("fee"), sl.get("fee"));
        event(appt, "BOOK", null, "PENDING_PAYMENT", a.id, null);
        idemDone(a, "BOOK", key, appt);
        return appointmentResult(appt);
    }

    private Map<String, Object> appointmentResult(long id) {
        return Map.of("code", 0, "appointment", row("SELECT * FROM appointment WHERE id=?", id), "bills", rows("SELECT id,bill_no,type,amount,status FROM bill WHERE appointment_id=?", id));
    }

    @GetMapping("/patient/appointments")
    public Map<String, Object> mine(HttpServletRequest req) {
        Actor a = actor(req, "PATIENT");
        return Map.of("code", 0, "items", rows("SELECT * FROM appointment WHERE patient_id=? ORDER BY created_at DESC LIMIT 100", a.profileId));
    }

    @GetMapping("/appointments/{id}")
    public Map<String, Object> detail(HttpServletRequest req, @PathVariable long id) {
        Actor a = actor(req, "PATIENT");
        row("SELECT id FROM appointment WHERE id=? AND patient_id=?", id, a.profileId);
        return appointmentResult(id);
    }

    @GetMapping("/patient/bills")
    public Map<String, Object> bills(HttpServletRequest req) {
        Actor a = actor(req, "PATIENT");
        return Map.of("code", 0, "items", rows("SELECT * FROM bill WHERE patient_id=? ORDER BY created_at DESC", a.profileId));
    }

    @GetMapping("/bills/{id}")
    public Map<String, Object> bill(HttpServletRequest req, @PathVariable long id) {
        Actor a = actor(req, "PATIENT");
        Map<String, Object> b = row("SELECT * FROM bill WHERE id=? AND patient_id=?", id, a.profileId);
        return Map.of("code", 0, "bill", b, "items", rows("SELECT item_code,item_name,quantity,unit_price,line_amount FROM bill_item WHERE bill_id=?", id));
    }

    @PostMapping("/bills/{id}/mock-pay")
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String, Object> pay(HttpServletRequest req, @PathVariable long id, @RequestHeader("Idempotency-Key") String key) {
        Actor a = actor(req, "PATIENT");
        long old = idem(a, "PAY", key, Long.toString(id));
        if (old > 0) return bill(req, old);
        check(old == 0, "请求正在处理");
        row("SELECT a.id FROM appointment a JOIN bill b ON b.appointment_id=a.id WHERE b.id=? AND b.patient_id=? FOR UPDATE", id, a.profileId);
        Map<String, Object> b = row("SELECT * FROM bill WHERE id=? AND patient_id=? FOR UPDATE", id, a.profileId);
        check("UNPAID".equals(b.get("status")), "账单不可支付");
        if ("REGISTRATION".equals(b.get("type"))) {
            Map<String, Object> ap = row("SELECT * FROM appointment WHERE id=? FOR UPDATE", b.get("appointment_id"));
            check("PENDING_PAYMENT".equals(ap.get("status")) && dateTime(ap.get("pay_expires_at")).isAfter(utcNow()), "预约已过支付截止");
            db.update("UPDATE appointment SET status='BOOKED' WHERE id=?", b.get("appointment_id"));
            event(num(ap, "id"), "PAY", "PENDING_PAYMENT", "BOOKED", a.id, null);
        }
        db.update("UPDATE bill SET status='PAID',paid_at=UTC_TIMESTAMP(3) WHERE id=?", id);
        db.update("INSERT INTO payment_record(bill_id,serial_no,amount,method,occurred_at) VALUES(?,?,?,'MOCK',UTC_TIMESTAMP(3))", id, "P" + UUID.randomUUID().toString().replace("-", ""), b.get("amount"));
        idemDone(a, "PAY", key, id);
        return bill(req, id);
    }

    @PutMapping("/appointments/{id}/cancel")
    @Transactional
    public Map<String, Object> cancel(HttpServletRequest req, @PathVariable long id, @RequestBody(required = false) Map<String, Object> m) {
        Actor a = actor(req, "PATIENT");
        Map<String, Object> ap = row("SELECT * FROM appointment WHERE id=? AND patient_id=? FOR UPDATE", id, a.profileId);
        String status = str(ap, "status");
        if ("CANCELLED".equals(status)) return appointmentResult(id);
        check(status.equals("PENDING_PAYMENT") || status.equals("BOOKED"), "当前状态不能取消");
        if (status.equals("BOOKED")) check(!utcNow().isAfter(dateTime(ap.get("cancel_deadline_at"))), "已过取消截止");
        db.update("UPDATE appointment SET status='CANCELLED' WHERE id=?", id);
        db.update("UPDATE schedule_slot SET remaining=remaining+1 WHERE id=?", ap.get("slot_id"));
        Map<String, Object> b = row("SELECT * FROM bill WHERE appointment_id=? AND type='REGISTRATION' FOR UPDATE", id);
        if ("PAID".equals(b.get("status"))) {
            Map<String, Object> payment = row("SELECT id FROM payment_record WHERE bill_id=?", b.get("id"));
            db.update("INSERT INTO refund_record(bill_id,payment_id,serial_no,amount,reason,occurred_at) VALUES(?,?,?,?,?,UTC_TIMESTAMP(3))", b.get("id"), payment.get("id"), "R" + UUID.randomUUID().toString().replace("-", ""), b.get("amount"), m == null ? null : m.get("reason"));
            db.update("UPDATE bill SET status='REFUNDED' WHERE id=?", b.get("id"));
        } else db.update("UPDATE bill SET status='CLOSED' WHERE id=?", b.get("id"));
        event(id, "CANCEL", status, "CANCELLED", a.id, m == null ? null : str(m, "reason"));
        return appointmentResult(id);
    }

    @PutMapping("/admin/appointments/{id}/check-in")
    @Transactional
    public Map<String, Object> checkin(HttpServletRequest req, @PathVariable long id) {
        Actor a = actor(req, "ADMIN");
        Map<String, Object> ap = row("SELECT * FROM appointment WHERE id=? FOR UPDATE", id);
        if ("CHECKED_IN".equals(ap.get("status"))) return appointmentResult(id);
        check("BOOKED".equals(ap.get("status")), "预约不可签到");
        LocalDateTime now = utcNow();
        check(!now.isBefore(dateTime(ap.get("check_in_opens_at"))) && now.isBefore(dateTime(ap.get("ends_at"))), "不在签到时段");
        check(id("SELECT COUNT(*) FROM schedule_slot sl JOIN schedule s ON s.id=sl.schedule_id WHERE sl.id=? AND s.status='PUBLISHED'", ap.get("slot_id")) == 1, "排班已停诊");
        db.update("UPDATE appointment SET status='CHECKED_IN',checked_in_at=UTC_TIMESTAMP(3) WHERE id=?", id);
        event(id, "CHECK_IN", "BOOKED", "CHECKED_IN", a.id, null);
        return appointmentResult(id);
    }

    @GetMapping("/doctor/appointments")
    public Map<String, Object> doctorAppointments(HttpServletRequest req) {
        Actor a = actor(req, "DOCTOR");
        return Map.of("code", 0, "items", rows("SELECT * FROM appointment WHERE doctor_id=? AND visit_date=? ORDER BY starts_at", a.profileId, LocalDate.now(HOSPITAL)));
    }

    @GetMapping("/doctor/treatment-items")
    public Map<String, Object> doctorTreatmentItems(HttpServletRequest req) {
        actor(req, "DOCTOR");
        return Map.of("code", 0, "items", rows("SELECT id,code,name,fee FROM treatment_item WHERE enabled=TRUE ORDER BY id"));
    }

    @PutMapping("/doctor/appointments/{id}/call")
    @Transactional
    public Map<String, Object> call(HttpServletRequest req, @PathVariable long id) {
        Actor a = actor(req, "DOCTOR");
        Map<String, Object> ap = row("SELECT * FROM appointment WHERE id=? AND doctor_id=? FOR UPDATE", id, a.profileId);
        check("CHECKED_IN".equals(ap.get("status")), "患者尚未签到");
        if (ap.get("called_at") == null) {
            db.update("UPDATE appointment SET called_at=UTC_TIMESTAMP(3) WHERE id=?", id);
            event(id, "CALL", "CHECKED_IN", "CHECKED_IN", a.id, null);
        }
        return appointmentResult(id);
    }

    @PutMapping("/doctor/appointments/{id}/start")
    @Transactional
    public Map<String, Object> start(HttpServletRequest req, @PathVariable long id) {
        Actor a = actor(req, "DOCTOR");
        Map<String, Object> ap = row("SELECT * FROM appointment WHERE id=? AND doctor_id=? FOR UPDATE", id, a.profileId);
        if ("IN_VISIT".equals(ap.get("status"))) return appointmentResult(id);
        check("CHECKED_IN".equals(ap.get("status")), "患者尚未签到");
        db.update("UPDATE appointment SET status='IN_VISIT',visit_started_at=UTC_TIMESTAMP(3) WHERE id=?", id);
        event(id, "START", "CHECKED_IN", "IN_VISIT", a.id, null);
        return appointmentResult(id);
    }

    @PostMapping("/doctor/appointments/{id}/medical-record/submit")
    @Transactional
    public Map<String, Object> submit(HttpServletRequest req, @PathVariable long id, @RequestHeader("Idempotency-Key") String key, @RequestBody Map<String, Object> m) {
        Actor a = actor(req, "DOCTOR");
        long old = idem(a, "RECORD", key, id + "|" + m.toString());
        if (old > 0) return Map.of("code", 0, "recordId", Long.toString(old));
        check(old == 0, "请求正在处理");
        Map<String, Object> ap = row("SELECT * FROM appointment WHERE id=? AND doctor_id=? FOR UPDATE", id, a.profileId);
        check("IN_VISIT".equals(ap.get("status")), "未开始接诊");
        long record = insert("INSERT INTO medical_record(appointment_id,doctor_id,chief_complaint,examination,assessment,treatment_note,advice,follow_up_date,published_at) VALUES(?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3))", id, a.profileId, required(m, "chiefComplaint"), required(m, "examination"), m.get("assessment"), m.get("treatmentNote"), required(m, "advice"), m.get("followUpDate"));
        if (m.containsKey("items") && m.get("items") instanceof List<?> items && !items.isEmpty()) {
            BigDecimal total = BigDecimal.ZERO;
            List<Map<String, Object>> selected = new ArrayList<>();
            for (Object raw : items) {
                Map<String, Object> item = (Map<String, Object>) raw;
                Map<String, Object> catalog = row("SELECT * FROM treatment_item WHERE id=? AND enabled=TRUE", Long.parseLong(required(item, "itemId")));
                int qty = Integer.parseInt(required(item, "quantity"));
                check(qty >= 1 && qty <= 20, "数量错误");
                BigDecimal line = ((BigDecimal) catalog.get("fee")).multiply(BigDecimal.valueOf(qty));
                total = total.add(line);
                selected.add(Map.of("catalog", catalog, "quantity", qty, "line", line));
            }
            long bill = insert("INSERT INTO bill(bill_no,appointment_id,patient_id,type,amount,status) VALUES(?,?,?,'TREATMENT',?,'UNPAID')", "T" + UUID.randomUUID().toString().replace("-", ""), id, ap.get("patient_id"), total);
            for (Map<String, Object> item : selected) {
                Map<String, Object> cat = (Map<String, Object>) item.get("catalog");
                db.update("INSERT INTO bill_item(bill_id,item_code,item_name,quantity,unit_price,line_amount) VALUES(?,?,?,?,?,?)", bill, cat.get("code"), cat.get("name"), item.get("quantity"), cat.get("fee"), item.get("line"));
            }
        }
        db.update("UPDATE appointment SET status='COMPLETED',completed_at=UTC_TIMESTAMP(3) WHERE id=?", id);
        event(id, "COMPLETE", "IN_VISIT", "COMPLETED", a.id, null);
        idemDone(a, "RECORD", key, record);
        return Map.of("code", 0, "recordId", Long.toString(record));
    }

    @GetMapping("/patient/medical-records")
    public Map<String, Object> records(HttpServletRequest req) {
        Actor a = actor(req, "PATIENT");
        return Map.of("code", 0, "items", rows("SELECT r.* FROM medical_record r JOIN appointment a ON a.id=r.appointment_id WHERE a.patient_id=? ORDER BY r.published_at DESC", a.profileId));
    }

    @GetMapping("/medical-records/{id}")
    public Map<String,Object> record(HttpServletRequest req,@PathVariable long id) {
        Actor a=actor(req,null);
        if ("ADMIN".equals(a.role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"无权访问就诊记录正文");
        Map<String,Object> record="PATIENT".equals(a.role)
                ? row("SELECT r.* FROM medical_record r JOIN appointment ap ON ap.id=r.appointment_id WHERE r.id=? AND ap.patient_id=?",id,a.profileId)
                : row("SELECT r.* FROM medical_record r WHERE r.id=? AND r.doctor_id=?",id,a.profileId);
        return Map.of("code",0,"record",record);
    }

    @GetMapping("/admin/dashboard/summary")
    public Map<String, Object> dashboard(HttpServletRequest req) {
        actor(req, "ADMIN");
        return Map.of("code", 0, "todayAppointments", id("SELECT COUNT(*) FROM appointment WHERE visit_date=?", LocalDate.now(HOSPITAL)), "completed", id("SELECT COUNT(*) FROM appointment WHERE visit_date=? AND status='COMPLETED'", LocalDate.now(HOSPITAL)), "pendingPayment", id("SELECT COUNT(*) FROM appointment WHERE status='PENDING_PAYMENT'"));
    }

    @GetMapping("/admin/statistics/appointments")
    public Map<String, Object> stats(HttpServletRequest req, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        actor(req, "ADMIN");
        check(!to.isBefore(from) && !to.isAfter(from.plusDays(89)), "日期范围最多90天");
        return Map.of("code", 0, "asOf", OffsetDateTime.now(ZoneOffset.UTC).toString(), "items", rows("SELECT visit_date,department_id,COUNT(*) total,SUM(status='COMPLETED') completed,SUM(status='CANCELLED') cancelled,SUM(status='EXPIRED') expired FROM appointment WHERE visit_date BETWEEN ? AND ? GROUP BY visit_date,department_id ORDER BY visit_date,department_id", from, to));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> duplicate() {
        return ResponseEntity.status(409).body(Map.of("code", 40903, "message", "记录已存在"));
    }
}

@EnableWebSecurity
class SecurityConfig extends WebSecurityConfigurerAdapter {
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.csrf().disable().authorizeRequests().anyRequest().permitAll();
    }
}
