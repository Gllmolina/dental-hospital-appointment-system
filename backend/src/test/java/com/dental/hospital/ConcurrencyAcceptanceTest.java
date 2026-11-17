package com.dental.hospital;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrencyAcceptanceTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv().getOrDefault("TEST_DB_URL", "jdbc:mysql://127.0.0.1:3306/dental_hospital_test?serverTimezone=UTC"));
        r.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USER", "root"));
        r.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
        r.add("JWT_SECRET", () -> "test-only-secret-with-more-than-thirty-two-bytes-2026");
        r.add("ADMIN_USERNAME", () -> "test_admin");
        r.add("ADMIN_PHONE", () -> "13999999999");
        r.add("ADMIN_PASSWORD", () -> "TestAdminPassword123!");
    }

    @Autowired
    JdbcTemplate db;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtTokens jwt;
    @Autowired
    Bootstrap bootstrap;
    @Autowired
    ReminderService reminders;
    @LocalServerPort
    int port;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final AtomicInteger PHONE = new AtomicInteger((int) (System.currentTimeMillis() % 70_000_000));
    private static final String PASSWORD_HASH = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("Password123!");

    record User(long userId, long profileId, String token) {
    }

    record Fixture(long doctorId, long roomId, long departmentId, long scheduleId, long slotId, LocalDateTime start) {
    }

    private String base() {
        return "http://127.0.0.1:" + port + "/api";
    }

    private String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String sha(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long insert(String sql, Object... args) {
        GeneratedKeyHolder h = new GeneratedKeyHolder();
        db.update(c -> {
            PreparedStatement p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
            return p;
        }, h);
        return Objects.requireNonNull(h.getKey()).longValue();
    }

    private long count(String sql, Object... args) {
        return Objects.requireNonNull(db.queryForObject(sql, Long.class, args));
    }

    private User user(String role, long department) {
        String name = unique("t");
        String phone = "139" + String.format("%08d", PHONE.incrementAndGet());
        long id = insert("INSERT INTO sys_user(username,phone,password_hash,role) VALUES(?,?,?,?)", name, phone, PASSWORD_HASH, role);
        long profile = 0;
        if (role.equals("PATIENT"))
            profile = insert("INSERT INTO patient_profile(user_id,name) VALUES(?,?)", id, "虚构患者");
        if (role.equals("DOCTOR"))
            profile = insert("INSERT INTO doctor_profile(user_id,employee_no,department_id,name,default_fee) VALUES(?,?,?,?,?)", id, unique("E"), department, "虚构医生", new BigDecimal("30.00"));
        String jti = UUID.randomUUID().toString(), token = jwt.issue(id, jti, Instant.now(), Instant.now().plusSeconds(3600));
        db.update("INSERT INTO auth_session(user_id,jti,token_hash,expires_at) VALUES(?,?,?,?)", id, jti, sha(token), LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        return new User(id, profile, token);
    }

    private LocalDateTime future() {
        return LocalDate.now(ZONE).plusDays(2).atTime(9, 0).atZone(ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private Fixture fixture(int capacity, LocalDateTime start) {
        long dep = insert("INSERT INTO department(code,name) VALUES(?,?)", unique("D"), "测试科室");
        long room = insert("INSERT INTO clinic_room(code,name) VALUES(?,?)", unique("R"), "测试诊室");
        User doctor = user("DOCTOR", dep);
        LocalDate day = start.atZone(ZoneOffset.UTC).withZoneSameInstant(ZONE).toLocalDate();
        long schedule = insert("INSERT INTO schedule(doctor_id,department_id,room_id,visit_date,starts_at,ends_at,status,fee,capacity) VALUES(?,?,?,?,?,?,'PUBLISHED',?,?)", doctor.profileId, dep, room, day, start, start.plusMinutes(30), new BigDecimal("30.00"), capacity);
        long slot = insert("INSERT INTO schedule_slot(schedule_id,starts_at,ends_at,capacity,remaining,fee) VALUES(?,?,?,?,?,?)", schedule, start, start.plusMinutes(30), capacity, capacity, new BigDecimal("30.00"));
        return new Fixture(doctor.profileId, room, dep, schedule, slot, start);
    }

    private HttpHeaders headers(String token, String idem) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        if (idem != null) h.set("Idempotency-Key", idem);
        return h;
    }

    private ResponseEntity<Map> http(String method, String path, String token, String idem, Object body) {
        return rest.exchange(base() + path, HttpMethod.valueOf(method), new HttpEntity<>(body, headers(token, idem)), Map.class);
    }

    private long appointmentId(ResponseEntity<Map> r) {
        assertEquals(200, r.getStatusCodeValue(), String.valueOf(r.getBody()));
        return ((Number) ((Map) r.getBody().get("appointment")).get("id")).longValue();
    }

    private long billId(ResponseEntity<Map> r) {
        return ((Number) ((Map) ((List<?>) r.getBody().get("bills")).get(0)).get("id")).longValue();
    }

    private List<ResponseEntity<Map>> concurrent(int n, Callable<ResponseEntity<Map>> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<Map>>> jobs = new ArrayList<>();
            for (int i = 0; i < n; i++)
                jobs.add(pool.submit(() -> {
                    gate.await();
                    return action.call();
                }));
            gate.countDown();
            List<ResponseEntity<Map>> result = new ArrayList<>();
            for (Future<ResponseEntity<Map>> f : jobs) result.add(f.get(30, TimeUnit.SECONDS));
            return result;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void t01ConcurrentSchedulePublish() throws Exception {
        Fixture f = fixture(1, future());
        User admin = user("ADMIN", 0);
        db.update("UPDATE schedule SET status='DRAFT' WHERE id=?", f.scheduleId);
        db.update("DELETE FROM schedule_slot WHERE id=?", f.slotId);
        long second = insert("INSERT INTO schedule(doctor_id,department_id,room_id,visit_date,starts_at,ends_at,status,fee,capacity) VALUES(?,?,?,?,?,?,'DRAFT',?,?)", f.doctorId, f.departmentId, f.roomId, f.start.atZone(ZoneOffset.UTC).withZoneSameInstant(ZONE).toLocalDate(), f.start, f.start.plusMinutes(30), new BigDecimal("30.00"), 1);
        List<Long> ids = List.of(f.scheduleId, second);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<Map>>> jobs = new ArrayList<>();
            for (long id : ids)
                jobs.add(pool.submit(() -> {
                    gate.await();
                    return http("PUT", "/admin/schedules/" + id + "/publish", admin.token, null, Map.of("version", 0));
                }));
            gate.countDown();
            int success = 0;
            for (var job : jobs) {
                int code = job.get(20, TimeUnit.SECONDS).getStatusCodeValue();
                assertTrue(code == 200 || code == 409, "unexpected status " + code);
                if (code == 200) success++;
            }
            assertEquals(1, success);
            assertEquals(1, count("SELECT COUNT(*) FROM schedule_slot WHERE schedule_id IN (?,?)", f.scheduleId, second));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void t02LastSlotOnlyOneWinner() throws Exception {
        Fixture f = fixture(1, future());
        List<User> patients = IntStream.range(0, 20).mapToObj(i -> user("PATIENT", 0)).toList();
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<Map>>> jobs = new ArrayList<>();
            for (User p : patients)
                jobs.add(pool.submit(() -> {
                    gate.await();
                    return http("POST", "/appointments", p.token, UUID.randomUUID().toString(), Map.of("slotId", Long.toString(f.slotId)));
                }));
            gate.countDown();
            int success = 0;
            for (var job : jobs) {
                int code = job.get(40, TimeUnit.SECONDS).getStatusCodeValue();
                assertTrue(code == 200 || code == 409, "unexpected status " + code);
                if (code == 200) success++;
            }
            assertEquals(1, success);
            assertEquals(0, count("SELECT remaining FROM schedule_slot WHERE id=?", f.slotId));
            assertEquals(1, count("SELECT COUNT(*) FROM appointment WHERE slot_id=?", f.slotId));
            assertEquals(1, count("SELECT COUNT(*) FROM bill b JOIN appointment a ON a.id=b.appointment_id WHERE a.slot_id=?", f.slotId));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void t03SamePatientOverlapAndAdjacent() throws Exception {
        LocalDateTime start = future();
        Fixture a = fixture(1, start), b = fixture(1, start), adjacent = fixture(1, start.plusMinutes(30));
        User p = user("PATIENT", 0);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<Map>>> jobs = new ArrayList<>();
            for (long slot : List.of(a.slotId, b.slotId))
                jobs.add(pool.submit(() -> {
                    gate.await();
                    return http("POST", "/appointments", p.token, UUID.randomUUID().toString(), Map.of("slotId", Long.toString(slot)));
                }));
            gate.countDown();
            int success = 0;
            for (var job : jobs) {
                int code = job.get(20, TimeUnit.SECONDS).getStatusCodeValue();
                assertTrue(code == 200 || code == 409, "unexpected status " + code);
                if (code == 200) success++;
            }
            assertEquals(1, success);
            assertEquals(200, http("POST", "/appointments", p.token, UUID.randomUUID().toString(), Map.of("slotId", Long.toString(adjacent.slotId))).getStatusCodeValue());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void t04IdempotencyAndConflict() {
        Fixture f = fixture(2, future());
        User p = user("PATIENT", 0);
        String k = UUID.randomUUID().toString();
        Map<String, String> request = Map.of("slotId", Long.toString(f.slotId));
        ResponseEntity<Map> first = http("POST", "/appointments", p.token, k, request), again = http("POST", "/appointments", p.token, k, request);
        assertEquals(appointmentId(first), appointmentId(again));
        assertEquals(409, http("POST", "/appointments", p.token, k, Map.of("slotId", Long.toString(f.slotId), "symptomSummary", "different")).getStatusCodeValue());
        assertEquals(1, count("SELECT remaining FROM schedule_slot WHERE id=?", f.slotId));
    }

    @Test
    void t05ExpiryWinsAtDeadline() throws Exception {
        Fixture f = fixture(1, future());
        User p = user("PATIENT", 0);
        ResponseEntity<Map> booked = http("POST", "/appointments", p.token, UUID.randomUUID().toString(), Map.of("slotId", Long.toString(f.slotId)));
        long appt = appointmentId(booked), bill = billId(booked);
        db.update("UPDATE appointment SET pay_expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?", appt);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<ResponseEntity<Map>> pay = pool.submit(() -> {
                gate.await();
                return http("POST", "/bills/" + bill + "/mock-pay", p.token, UUID.randomUUID().toString(), Map.of());
            });
            Future<?> expire = pool.submit(() -> {
                gate.await();
                bootstrap.expire();
                return null;
            });
            gate.countDown();
            assertNotEquals(200, pay.get(20, TimeUnit.SECONDS).getStatusCodeValue());
            expire.get(20, TimeUnit.SECONDS);
            assertEquals("EXPIRED", db.queryForObject("SELECT status FROM appointment WHERE id=?", String.class, appt));
            assertEquals(1, count("SELECT remaining FROM schedule_slot WHERE id=?", f.slotId));
            assertEquals(0, count("SELECT COUNT(*) FROM payment_record WHERE bill_id=?", bill));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void t06RepeatCancelRefundAndRebook() {
        Fixture f = fixture(1, future());
        User p = user("PATIENT", 0);
        ResponseEntity<Map> booked = http("POST", "/appointments", p.token, UUID.randomUUID().toString(), Map.of("slotId", Long.toString(f.slotId)));
        long appt = appointmentId(booked), bill = billId(booked);
        assertEquals(200, http("POST", "/bills/" + bill + "/mock-pay", p.token, UUID.randomUUID().toString(), Map.of()).getStatusCodeValue());
        assertEquals(200, http("PUT", "/appointments/" + appt + "/cancel", p.token, null, Map.of()).getStatusCodeValue());
        assertEquals(200, http("PUT", "/appointments/" + appt + "/cancel", p.token, null, Map.of()).getStatusCodeValue());
        assertEquals(1, count("SELECT COUNT(*) FROM refund_record WHERE bill_id=?", bill));
        assertEquals(1, count("SELECT remaining FROM schedule_slot WHERE id=?", f.slotId));
        assertEquals(200, http("POST", "/appointments", p.token, UUID.randomUUID().toString(), Map.of("slotId", Long.toString(f.slotId))).getStatusCodeValue());
    }

    private ResponseEntity<Map> reserve(User patient, Fixture fixture) {
        return http("POST", "/appointments", patient.token, UUID.randomUUID().toString(), Map.of("slotId", fixture.slotId));
    }

    private int payRegistration(User patient, long bill) {
        return http("POST", "/bills/" + bill + "/mock-pay", patient.token, UUID.randomUUID().toString(), Map.of()).getStatusCodeValue();
    }

    private ResponseEntity<Map> suspend(User admin, Fixture fixture) {
        return http("POST", "/admin/schedules/" + fixture.scheduleId + "/suspend", admin.token, null, Map.of("reason", "停诊验收"));
    }

    @Test
    void t07RescheduleRejectsInvalidTargetsAndMovesOnlyOneSeatAndKeepsPaidBill() throws Exception {
        LocalDateTime start = future();
        Fixture source = fixture(1, start), full = fixture(0, start.plusHours(1));
        Fixture expensive = fixture(1, start.plusHours(2)), valid = fixture(1, start.plusDays(1));
        db.update("UPDATE schedule_slot SET fee=45 WHERE id=?", expensive.slotId);
        User patient = user("PATIENT", 0), stranger = user("PATIENT", 0);
        var booked = reserve(patient, source);
        long appointment = appointmentId(booked), bill = billId(booked);
        assertEquals(200, payRegistration(patient, bill));
        String path = "/appointments/" + appointment + "/reschedule";
        assertEquals(404, http("GET", path + "-options", stranger.token, null, null).getStatusCodeValue());
        assertEquals(200, http("GET", path + "-options", patient.token, null, null).getStatusCodeValue());
        for (Fixture rejected : List.of(full, expensive)) {
            assertEquals(409, http("PUT", path, patient.token, UUID.randomUUID().toString(),
                    Map.of("targetSlotId", rejected.slotId)).getStatusCodeValue());
            assertEquals(source.slotId, count("SELECT slot_id FROM appointment WHERE id=?", appointment));
            assertEquals(0, count("SELECT remaining FROM schedule_slot WHERE id=?", source.slotId));
        }
        String key = UUID.randomUUID().toString();
        var moved = concurrent(2, () -> http("PUT", path, patient.token, key, Map.of("targetSlotId", valid.slotId)));
        for (var result : moved) assertEquals(appointment, appointmentId(result));
        assertEquals(409, http("PUT", path, patient.token, key, Map.of("targetSlotId", source.slotId)).getStatusCodeValue());
        assertEquals(valid.slotId, count("SELECT slot_id FROM appointment WHERE id=?", appointment));
        assertEquals(valid.doctorId, count("SELECT doctor_id FROM appointment WHERE id=?", appointment));
        assertEquals(1, count("SELECT remaining FROM schedule_slot WHERE id=?", source.slotId));
        assertEquals(0, count("SELECT remaining FROM schedule_slot WHERE id=?", valid.slotId));
        assertEquals(1, count("SELECT COUNT(*) FROM bill WHERE appointment_id=? AND id=? AND status='PAID'", appointment, bill));
        assertEquals(1, count("SELECT COUNT(*) FROM appointment_status_log WHERE appointment_id=? AND event_type='RESCHEDULE'", appointment));
        assertEquals(1, count("SELECT COUNT(*) FROM system_message WHERE appointment_id=? AND type='RESCHEDULED'", appointment));
    }

    @Test
    void t08SuspensionBlocksPaymentAndCreatesOneTaskPerPaidAppointment() throws Exception {
        Fixture source = fixture(4, future());
        User admin = user("ADMIN", 0), paid = user("PATIENT", 0), late = user("PATIENT", 0);
        User racing = user("PATIENT", 0), refund = user("PATIENT", 0);
        var paidBooking = reserve(paid, source);
        var lateBooking = reserve(late, source);
        var racingBooking = reserve(racing, source);
        var refundBooking = reserve(refund, source);
        long paidId = appointmentId(paidBooking), lateId = appointmentId(lateBooking);
        long racingId = appointmentId(racingBooking), refundId = appointmentId(refundBooking);
        assertEquals(200, payRegistration(paid, billId(paidBooking)));
        assertEquals(200, payRegistration(refund, billId(refundBooking)));
        AtomicInteger index = new AtomicInteger();
        var results = concurrent(2, () -> index.getAndIncrement() == 0 ? suspend(admin, source)
                : http("POST", "/bills/" + billId(racingBooking) + "/mock-pay", racing.token, UUID.randomUUID().toString(), null));
        assertEquals(1, results.stream().filter(r -> "SUSPENDED".equals(r.getBody().get("status"))).count());
        assertTrue(results.stream().allMatch(r -> Set.of(200, 409).contains(r.getStatusCodeValue())));
        assertEquals(409, payRegistration(late, billId(lateBooking)));
        assertEquals(409, reserve(user("PATIENT", 0), source).getStatusCodeValue());
        bootstrap.scanDisruptions();
        bootstrap.scanDisruptions();
        assertEquals(1, count("SELECT COUNT(*) FROM appointment_disruption WHERE appointment_id=? AND status='OPEN'", paidId));
        assertEquals(1, count("SELECT COUNT(*) FROM system_message WHERE appointment_id=? AND type='SUSPENDED'", paidId));
        assertEquals("CANCELLED", db.queryForObject("SELECT status FROM appointment WHERE id=?", String.class, lateId));
        boolean paymentWon = count("SELECT COUNT(*) FROM payment_record WHERE bill_id=?", billId(racingBooking)) == 1;
        assertEquals(paymentWon ? "BOOKED" : "CANCELLED", db.queryForObject("SELECT status FROM appointment WHERE id=?", String.class, racingId));
        assertEquals(paymentWon ? 1 : 0, count("SELECT COUNT(*) FROM appointment_disruption WHERE appointment_id=?", racingId));
        assertEquals(200, http("GET", "/admin/disruptions?scheduleId=" + source.scheduleId, admin.token, null, null).getStatusCodeValue());
        assertEquals(200, http("GET", "/patient/messages?unread=true", paid.token, null, null).getStatusCodeValue());
        long messageId = count("SELECT id FROM system_message WHERE appointment_id=? AND type='SUSPENDED'", paidId);
        assertEquals(404, http("PUT", "/patient/messages/" + messageId + "/read", late.token, null, null).getStatusCodeValue());
        assertEquals(200, http("PUT", "/patient/messages/" + messageId + "/read", paid.token, null, null).getStatusCodeValue());
        Fixture target = fixture(1, future().plusDays(1));
        assertEquals(200, http("GET", "/admin/appointments/" + paidId + "/reschedule-options",
                admin.token, null, null).getStatusCodeValue());
        long task = count("SELECT id FROM appointment_disruption WHERE appointment_id=?", paidId);
        assertEquals(200, http("PUT", "/admin/appointments/" + paidId + "/reschedule", admin.token, UUID.randomUUID().toString(),
                Map.of("targetSlotId", target.slotId, "disruptionId", task, "reason", "患者确认新时段")).getStatusCodeValue());
        assertEquals(1, count("SELECT COUNT(*) FROM appointment_disruption WHERE appointment_id=? AND status='RESCHEDULED'", paidId));
        assertEquals(409, http("PUT", "/admin/appointments/" + paidId + "/cancel", admin.token, null,
                Map.of("disruptionId", task, "reason", "旧页面上的取消操作")).getStatusCodeValue());
        assertEquals("BOOKED", db.queryForObject("SELECT status FROM appointment WHERE id=?", String.class, paidId));
        String cancellation = "/admin/appointments/" + refundId + "/cancel";
        for (var result : concurrent(2, () -> http("PUT", cancellation, admin.token, null, Map.of("reason", "患者确认退款")))) {
            assertEquals(200, result.getStatusCodeValue());
        }
        assertEquals(1, count("SELECT COUNT(*) FROM refund_record WHERE bill_id=?", billId(refundBooking)));
        assertEquals(1, count("SELECT COUNT(*) FROM appointment_disruption WHERE appointment_id=? AND status='CANCELLED'", refundId));
    }

    @Test
    void t08ConcurrentCheckInAndSuspensionAreMutuallyExclusive() throws Exception {
        Fixture f = fixture(1, LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20));
        User patient = user("PATIENT", 0), admin = user("ADMIN", 0);
        var booking = reserve(patient, f);
        long id = appointmentId(booking);
        assertEquals(200, payRegistration(patient, billId(booking)));
        AtomicInteger index = new AtomicInteger();
        var outcomes = concurrent(2, () -> index.getAndIncrement() == 0 ? suspend(admin, f)
                : http("PUT", "/admin/appointments/" + id + "/check-in", admin.token, null, null));
        assertEquals(List.of(200, 409), outcomes.stream().map(ResponseEntity::getStatusCodeValue).sorted().toList());
        String schedule = db.queryForObject("SELECT status FROM schedule WHERE id=?", String.class, f.scheduleId);
        String appointment = db.queryForObject("SELECT status FROM appointment WHERE id=?", String.class, id);
        assertEquals("SUSPENDED".equals(schedule) ? "BOOKED" : "CHECKED_IN", appointment);
    }

    @Test
    void t09RecordSubmitOnceAndOwnership() {
        LocalDateTime start = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20);
        Fixture f = fixture(1, start);
        User p = user("PATIENT", 0), admin = user("ADMIN", 0);
        ResponseEntity<Map> booked = http("POST", "/appointments", p.token, UUID.randomUUID().toString(), Map.of("slotId", Long.toString(f.slotId)));
        long appt = appointmentId(booked), bill = billId(booked);
        assertEquals(200, http("POST", "/bills/" + bill + "/mock-pay", p.token, UUID.randomUUID().toString(), Map.of()).getStatusCodeValue());
        assertEquals(200, http("PUT", "/admin/appointments/" + appt + "/check-in", admin.token, null, Map.of()).getStatusCodeValue());
        long doctorUser = count("SELECT user_id FROM doctor_profile WHERE id=?", f.doctorId);
        String jti = UUID.randomUUID().toString(), token = jwt.issue(doctorUser, jti, Instant.now(), Instant.now().plusSeconds(3600));
        db.update("INSERT INTO auth_session(user_id,jti,token_hash,expires_at) VALUES(?,?,?,?)", doctorUser, jti, sha(token), LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        assertEquals(200, http("PUT", "/doctor/appointments/" + appt + "/start", token, null, Map.of()).getStatusCodeValue());
        long item = insert("INSERT INTO treatment_item(code,name,fee) VALUES(?,?,?)", unique("I"), "检查", new BigDecimal("20.00"));
        Map<String, Object> body = Map.of("chiefComplaint", "虚构主诉", "examination", "虚构检查", "advice", "虚构医嘱", "items", List.of(Map.of("itemId", Long.toString(item), "quantity", "1")));
        String key = UUID.randomUUID().toString();
        assertEquals(200, http("POST", "/doctor/appointments/" + appt + "/medical-record/submit", token, key, body).getStatusCodeValue());
        assertEquals(200, http("POST", "/doctor/appointments/" + appt + "/medical-record/submit", token, key, body).getStatusCodeValue());
        assertEquals(1, count("SELECT COUNT(*) FROM medical_record WHERE appointment_id=?", appt));
        assertEquals(1, count("SELECT COUNT(*) FROM bill WHERE appointment_id=? AND type='TREATMENT'", appt));
        User other = user("PATIENT", 0);
        assertEquals(404, http("GET", "/appointments/" + appt, other.token, null, null).getStatusCodeValue());
        assertEquals(404, http("GET", "/bills/" + bill, other.token, null, null).getStatusCodeValue());
        long recordId = count("SELECT id FROM medical_record WHERE appointment_id=?", appt);
        assertEquals(404, http("GET", "/medical-records/" + recordId, other.token, null, null).getStatusCodeValue());
        assertEquals(403, http("GET", "/medical-records/" + recordId, admin.token, null, null).getStatusCodeValue());
    }

    @Test
    void t04ConcurrentSameKeyReturnsOneResult() throws Exception {
        Fixture f = fixture(2, future());
        User p = user("PATIENT", 0);
        String idem = UUID.randomUUID().toString();
        List<ResponseEntity<Map>> results = concurrent(2, () -> http("POST", "/appointments", p.token, idem, Map.of("slotId", Long.toString(f.slotId))));
        assertEquals(200, results.get(0).getStatusCodeValue());
        assertEquals(200, results.get(1).getStatusCodeValue());
        assertEquals(appointmentId(results.get(0)), appointmentId(results.get(1)));
        assertEquals(1, count("SELECT remaining FROM schedule_slot WHERE id=?", f.slotId));
    }

    @Test
    void t05BeforeAndAtDeadline() {
        Fixture early = fixture(1, future());
        User p = user("PATIENT", 0);
        ResponseEntity<Map> booked = http("POST", "/appointments", p.token, UUID.randomUUID().toString(), Map.of("slotId", Long.toString(early.slotId)));
        long appt = appointmentId(booked), bill = billId(booked);
        bootstrap.expire();
        assertEquals(200, http("POST", "/bills/" + bill + "/mock-pay", p.token, UUID.randomUUID().toString(), Map.of()).getStatusCodeValue());
        assertEquals("BOOKED", db.queryForObject("SELECT status FROM appointment WHERE id=?", String.class, appt));
        Fixture due = fixture(1, future());
        User p2 = user("PATIENT", 0);
        ResponseEntity<Map> second = http("POST", "/appointments", p2.token, UUID.randomUUID().toString(), Map.of("slotId", Long.toString(due.slotId)));
        long expired = appointmentId(second), dueBill = billId(second);
        db.update("UPDATE appointment SET pay_expires_at=UTC_TIMESTAMP(3) WHERE id=?", expired);
        assertEquals(409, http("POST", "/bills/" + dueBill + "/mock-pay", p2.token, UUID.randomUUID().toString(), Map.of()).getStatusCodeValue());
        bootstrap.expire();
        assertEquals("EXPIRED", db.queryForObject("SELECT status FROM appointment WHERE id=?", String.class, expired));
    }

    @Test
    void t06ConcurrentCancelOnlyOneRefund() throws Exception {
        Fixture f = fixture(1, future());
        User p = user("PATIENT", 0);
        ResponseEntity<Map> booked = http("POST", "/appointments", p.token, UUID.randomUUID().toString(), Map.of("slotId", Long.toString(f.slotId)));
        long appt = appointmentId(booked), bill = billId(booked);
        assertEquals(200, http("POST", "/bills/" + bill + "/mock-pay", p.token, UUID.randomUUID().toString(), Map.of()).getStatusCodeValue());
        List<ResponseEntity<Map>> results = concurrent(2, () -> http("PUT", "/appointments/" + appt + "/cancel", p.token, null, Map.of()));
        for (var r : results) assertEquals(200, r.getStatusCodeValue());
        assertEquals(1, count("SELECT COUNT(*) FROM refund_record WHERE bill_id=?", bill));
        assertEquals(1, count("SELECT remaining FROM schedule_slot WHERE id=?", f.slotId));
    }

    @Test
    void t14StatsCountCancelledOnce() {
        Fixture f = fixture(1, future());
        User p = user("PATIENT", 0), admin = user("ADMIN", 0);
        ResponseEntity<Map> booked = http("POST", "/appointments", p.token, UUID.randomUUID().toString(), Map.of("slotId", Long.toString(f.slotId)));
        long appt = appointmentId(booked);
        http("PUT", "/appointments/" + appt + "/cancel", p.token, null, Map.of());
        LocalDate date = f.start.atZone(ZoneOffset.UTC).withZoneSameInstant(ZONE).toLocalDate();
        ResponseEntity<Map> response = http("GET", "/admin/statistics/appointments?from=" + date + "&to=" + date, admin.token, null, null);
        assertEquals(200, response.getStatusCodeValue());
        List<Map> data = (List<Map>) response.getBody().get("items");
        Map line = data.stream().filter(x -> ((Number) x.get("department_id")).longValue() == f.departmentId).findFirst().orElseThrow();
        assertEquals(1, ((Number) line.get("total")).intValue());
        assertEquals(1, ((Number) line.get("cancelled")).intValue());
    }

    @Test
    void jwtTamperingRevocationAndAdminBoundary() {
        User a = user("ADMIN", 0), p = user("PATIENT", 0);
        assertEquals(200, http("GET", "/auth/me", p.token, null, null).getStatusCodeValue());
        String tampered = p.token.substring(0, p.token.length() - 2) + "xx";
        assertEquals(401, http("GET", "/auth/me", tampered, null, null).getStatusCodeValue());
        assertEquals(403, http("GET", "/admin/users", p.token, null, null).getStatusCodeValue());
        assertEquals(200, http("PUT", "/admin/users/" + p.userId + "/status", a.token, null, Map.of("status", "DISABLED", "reason", "验收测试")).getStatusCodeValue());
        assertEquals(401, http("GET", "/auth/me", p.token, null, null).getStatusCodeValue());
        assertEquals(1, count("SELECT COUNT(*) FROM admin_operation_log WHERE operator_id=? AND target_id=? AND action='USER_STATUS'", a.userId, p.userId));
    }

    @Test
    void adminCapacityCannotDropBelowUsed() {
        Fixture f = fixture(2, future());
        User a = user("ADMIN", 0), p = user("PATIENT", 0);
        assertEquals(200, http("POST", "/appointments", p.token, UUID.randomUUID().toString(), Map.of("slotId", Long.toString(f.slotId))).getStatusCodeValue());
        assertEquals(409, http("PUT", "/admin/slots/" + f.slotId + "/capacity", a.token, null, Map.of("capacity", 0)).getStatusCodeValue());
        assertEquals(200, http("PUT", "/admin/slots/" + f.slotId + "/capacity", a.token, null, Map.of("capacity", 1)).getStatusCodeValue());
        assertEquals(0, count("SELECT remaining FROM schedule_slot WHERE id=?", f.slotId));
        assertEquals(409, http("PUT", "/admin/rooms/" + f.roomId, a.token, null, Map.of("enabled", false)).getStatusCodeValue());
    }

    @Test
    void t11ReviewRequiresPaidTreatmentAndHidesAnonymousIdentity() {
        Fixture f = fixture(1, future());
        User patient = user("PATIENT", 0);
        User doctor = user("DOCTOR", f.departmentId);
        ResponseEntity<Map> created = http("POST", "/appointments", patient.token, UUID.randomUUID().toString(), Map.of("slotId", Long.toString(f.slotId)));
        long appointment = appointmentId(created);
        assertEquals(200, http("POST", "/bills/" + billId(created) + "/mock-pay", patient.token, UUID.randomUUID().toString(), Map.of()).getStatusCodeValue());
        db.update("UPDATE appointment SET status='COMPLETED' WHERE id=?", appointment);
        long treatment = insert("INSERT INTO bill(bill_no,appointment_id,patient_id,type,amount,status) VALUES(?,?,?,'TREATMENT',?,'UNPAID')",
                unique("T"), appointment, patient.profileId, new BigDecimal("50.00"));
        Map<String, Object> body = Map.of("serviceScore", 5, "communicationScore", 4, "overallScore", 5, "content", "测试评价", "anonymous", true);
        assertEquals(409, http("POST", "/appointments/" + appointment + "/review", patient.token, null, body).getStatusCodeValue());
        assertEquals(200, http("POST", "/bills/" + treatment + "/mock-pay", patient.token, UUID.randomUUID().toString(), Map.of()).getStatusCodeValue());
        assertEquals(200, http("POST", "/appointments/" + appointment + "/review", patient.token, null, body).getStatusCodeValue());
        assertEquals(409, http("POST", "/appointments/" + appointment + "/review", patient.token, null, body).getStatusCodeValue());
        assertEquals(1, count("SELECT COUNT(*) FROM review WHERE appointment_id=?", appointment));
        // A different doctor cannot see this review. The assigned doctor view is checked below.
        assertTrue(((List<?>) http("GET", "/doctor/reviews", doctor.token, null, null).getBody().get("items")).isEmpty());
        User assigned = userForDoctor(f.doctorId);
        ResponseEntity<Map> visible = http("GET", "/doctor/reviews", assigned.token, null, null);
        assertEquals(200, visible.getStatusCodeValue());
        Map<?, ?> item = (Map<?, ?>) ((List<?>) visible.getBody().get("items")).stream()
                .map(x -> (Map<?, ?>) x).filter(x -> ((Number) x.get("id")).longValue() ==
                        count("SELECT id FROM review WHERE appointment_id=?", appointment)).findFirst().orElseThrow();
        assertEquals("匿名患者", item.get("display_name"));
        assertFalse(item.containsKey("patient_id"));
        assertFalse(item.containsKey("appointment_id"));
    }

    private User userForDoctor(long doctorId) {
        long userId = count("SELECT user_id FROM doctor_profile WHERE id=?", doctorId);
        String token = jwt.issue(userId, UUID.randomUUID().toString(), Instant.now(), Instant.now().plusSeconds(3600));
        String jti = jwt.verify(token).jti();
        db.update("INSERT INTO auth_session(user_id,jti,token_hash,expires_at) VALUES(?,?,?,?)", userId, jti, sha(token), LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        return new User(userId, doctorId, token);
    }

    @Test
    void archiveVersionAndFavoriteOwnership() {
        Fixture f = fixture(1, future());
        User patient = user("PATIENT", 0), other = user("PATIENT", 0);
        assertEquals(0, ((Number) ((Map<?, ?>) http("GET", "/patient/health-archive", patient.token, null, null).getBody().get("archive")).get("version")).intValue());
        Map<String, Object> archive = Map.of("version", 0, "allergyHistory", "青霉素", "pastHistory", "无", "note", "测试档案");
        assertEquals(200, http("PUT", "/patient/health-archive", patient.token, null, archive).getStatusCodeValue());
        assertEquals(409, http("PUT", "/patient/health-archive", patient.token, null, archive).getStatusCodeValue());
        assertEquals(0, ((Number) ((Map<?, ?>) http("GET", "/patient/health-archive", other.token, null, null).getBody().get("archive")).get("version")).intValue());
        assertEquals(200, http("PUT", "/patient/favorites/" + f.doctorId, patient.token, null, Map.of()).getStatusCodeValue());
        assertEquals(200, http("PUT", "/patient/favorites/" + f.doctorId, patient.token, null, Map.of()).getStatusCodeValue());
        assertEquals(1, count("SELECT COUNT(*) FROM favorite_doctor WHERE patient_id=? AND doctor_id=?", patient.profileId, f.doctorId));
        assertTrue(((List<?>) http("GET", "/patient/favorites", other.token, null, null).getBody().get("items")).isEmpty());
    }

    @Test
    void leaveApprovalRequiresPublishedScheduleResolution() {
        Fixture f = fixture(1, future());
        User doctor = userForDoctor(f.doctorId), admin = user("ADMIN", 0);
        String start = f.start.minusMinutes(5).atOffset(ZoneOffset.UTC).toString();
        String end = f.start.plusMinutes(35).atOffset(ZoneOffset.UTC).toString();
        assertEquals(200, http("POST", "/doctor/leave-requests", doctor.token, null,
                Map.of("startsAt", start, "endsAt", end, "reason", "测试请假")).getStatusCodeValue());
        long leave = count("SELECT MAX(id) FROM leave_request WHERE doctor_id=?", f.doctorId);
        assertEquals(409, http("PUT", "/admin/leave-requests/" + leave + "/decision", admin.token, null,
                Map.of("decision", "APPROVED", "reviewNote", "同意")).getStatusCodeValue());
        assertEquals(200, http("POST", "/admin/schedules/" + f.scheduleId + "/suspend", admin.token, null,
                Map.of("reason", "请假调度")).getStatusCodeValue());
        assertEquals(200, http("PUT", "/admin/leave-requests/" + leave + "/decision", admin.token, null,
                Map.of("decision", "APPROVED", "reviewNote", "已完成调度")).getStatusCodeValue());
        long draft = insert("INSERT INTO schedule(doctor_id,department_id,room_id,visit_date,starts_at,ends_at,status,fee,capacity) VALUES(?,?,?,?,?,?,'DRAFT',?,?)",
                f.doctorId, f.departmentId, f.roomId,
                f.start.atZone(ZoneOffset.UTC).withZoneSameInstant(ZONE).toLocalDate(), f.start, f.start.plusMinutes(30), new BigDecimal("30.00"), 1);
        assertEquals(409, http("PUT", "/admin/schedules/" + draft + "/publish", admin.token, null,
                Map.of("version", "0")).getStatusCodeValue());
    }

    @Test
    void t12ReminderIsDeduplicatedAndRevokedOnCancellation() {
        Fixture f = fixture(1, future());
        User patient = user("PATIENT", 0);
        ResponseEntity<Map> created = http("POST", "/appointments", patient.token, UUID.randomUUID().toString(), Map.of("slotId", Long.toString(f.slotId)));
        long appointment = appointmentId(created);
        assertEquals(200, http("POST", "/bills/" + billId(created) + "/mock-pay", patient.token, UUID.randomUUID().toString(), Map.of()).getStatusCodeValue());
        LocalDate day = f.start.atZone(ZoneOffset.UTC).withZoneSameInstant(ZONE).toLocalDate();
        reminders.runFor(day);
        reminders.runFor(day);
        assertEquals(1, count("SELECT COUNT(*) FROM system_message WHERE appointment_id=? AND type='VISIT_REMINDER'", appointment));
        assertEquals(200, http("PUT", "/appointments/" + appointment + "/cancel", patient.token, null, Map.of()).getStatusCodeValue());
        assertEquals(1, count("SELECT COUNT(*) FROM system_message WHERE appointment_id=? AND type='VISIT_REMINDER' AND revoked_at IS NOT NULL", appointment));
        List<?> visible = (List<?>) http("GET", "/patient/messages", patient.token, null, null).getBody().get("items");
        assertTrue(visible.stream().map(x -> (Map<?, ?>) x).noneMatch(x -> "VISIT_REMINDER".equals(x.get("type")) && ((Number) x.get("appointment_id")).longValue() == appointment));
    }

    @Test
    void t12FaqUsesOnlyPublishedKnowledgeAndFallsBack() {
        User admin = user("ADMIN", 0), patient = user("PATIENT", 0), other = user("PATIENT", 0);
        String questionText = unique("独特问题");
        ResponseEntity<Map> session = http("POST", "/ai/sessions", patient.token, null, Map.of("title", "验收问答"));
        long sessionId = ((Number) session.getBody().get("id")).longValue();
        assertEquals(404, http("GET", "/ai/sessions/" + sessionId, other.token, null, null).getStatusCodeValue());
        ResponseEntity<Map> draft = http("POST", "/admin/knowledge-items", admin.token, null,
                Map.of("category", "流程", "question", questionText, "answer", "先选择医生和可约时段。", "keywords", "预约,验收"));
        long itemId = ((Number) draft.getBody().get("id")).longValue();
        Map<String, Object> question = Map.of("sessionId", Long.toString(sessionId), "question", questionText);
        assertEquals("FALLBACK", http("POST", "/ai/chat", patient.token, null, question).getBody().get("source"));
        assertEquals(200, http("PUT", "/admin/knowledge-items/" + itemId + "/publication", admin.token, null, Map.of()).getStatusCodeValue());
        ResponseEntity<Map> answer = http("POST", "/ai/chat", patient.token, null, question);
        assertEquals("FAQ", answer.getBody().get("source"));
        assertEquals("先选择医生和可约时段。", answer.getBody().get("answer"));
        assertEquals("SAFETY_NOTICE", http("POST", "/ai/chat", patient.token, null,
                Map.of("sessionId", Long.toString(sessionId), "question", "面部肿胀且呼吸困难怎么办")).getBody().get("source"));
        ResponseEntity<Map> revised = http("PUT", "/admin/knowledge-items/" + itemId, admin.token, null,
                Map.of("category", "流程", "question", questionText, "answer", "使用新版预约流程。", "keywords", "预约,验收"));
        assertEquals("先选择医生和可约时段。", http("POST", "/ai/chat", patient.token, null, question).getBody().get("answer"));
        long newId = ((Number) revised.getBody().get("id")).longValue();
        assertEquals(200, http("PUT", "/admin/knowledge-items/" + newId + "/publication", admin.token, null, Map.of()).getStatusCodeValue());
        assertEquals("使用新版预约流程。", http("POST", "/ai/chat", patient.token, null, question).getBody().get("answer"));
    }

    @Test
    void t13ColdStartRecommendationDropsFullDoctors() {
        Fixture one = fixture(1, future()), two = fixture(2, future().plusHours(1));
        User patient = user("PATIENT", 0), booking = user("PATIENT", 0);
        ResponseEntity<Map> first = http("GET", "/recommend/doctors?departmentId=" + one.departmentId, patient.token, null, null);
        assertEquals(200, first.getStatusCodeValue());
        List<Map> candidates = (List<Map>) first.getBody().get("items");
        assertTrue(candidates.stream().anyMatch(x -> ((Number) x.get("doctorId")).longValue() == one.doctorId));
        assertEquals(0.6, ((Number) ((Map) candidates.get(0).get("components")).get("rating")).doubleValue());
        assertEquals(200, http("POST", "/appointments", booking.token, UUID.randomUUID().toString(), Map.of("slotId", Long.toString(one.slotId))).getStatusCodeValue());
        ResponseEntity<Map> refreshed = http("GET", "/recommend/doctors?departmentId=" + one.departmentId, patient.token, null, null);
        assertEquals(200, refreshed.getStatusCodeValue());
        List<Map> current = (List<Map>) refreshed.getBody().get("items");
        assertTrue(current.stream().noneMatch(x -> ((Number) x.get("doctorId")).longValue() == one.doctorId));
        ResponseEntity<Map> otherDepartment = http("GET", "/recommend/doctors?departmentId=" + two.departmentId, patient.token, null, null);
        assertTrue(((List<Map>) otherDepartment.getBody().get("items")).stream().anyMatch(x -> ((Number) x.get("doctorId")).longValue() == two.doctorId));
    }

    @Test
    void t14OverviewUsesFilteredNumeratorsAndDenominators() {
        Fixture f = fixture(1, future());
        User admin = user("ADMIN", 0), patient = user("PATIENT", 0);
        ResponseEntity<Map> booked = http("POST", "/appointments", patient.token, UUID.randomUUID().toString(), Map.of("slotId", Long.toString(f.slotId)));
        long appointment = appointmentId(booked);
        assertEquals(200, http("PUT", "/appointments/" + appointment + "/cancel", patient.token, null, Map.of()).getStatusCodeValue());
        LocalDate day = f.start.atZone(ZoneOffset.UTC).withZoneSameInstant(ZONE).toLocalDate();
        String query = "/admin/statistics/overview?from=" + day + "&to=" + day + "&departmentId=" + f.departmentId;
        ResponseEntity<Map> result = http("GET", query, admin.token, null, null);
        assertEquals(200, result.getStatusCodeValue(), String.valueOf(result.getBody()));
        Map<?, ?> cancellation = (Map<?, ?>) result.getBody().get("cancellationRate");
        Map<?, ?> utilization = (Map<?, ?>) result.getBody().get("utilization");
        Map<?, ?> noShow = (Map<?, ?>) result.getBody().get("noShowRate");
        assertEquals(1, ((Number) cancellation.get("numerator")).intValue());
        assertEquals(1, ((Number) cancellation.get("denominator")).intValue());
        assertEquals(0, ((Number) utilization.get("numerator")).intValue());
        assertEquals(1, ((Number) utilization.get("denominator")).intValue());
        assertNull(noShow.get("value"));
    }

    @Test
    void t09DraftIsPrivateAndCorrectionRetainsVersions() {
        Fixture f = fixture(1, future());
        User patient = user("PATIENT", 0), doctor = userForDoctor(f.doctorId);
        ResponseEntity<Map> created = http("POST", "/appointments", patient.token, UUID.randomUUID().toString(), Map.of("slotId", Long.toString(f.slotId)));
        long appointment = appointmentId(created);
        db.update("UPDATE appointment SET status='CHECKED_IN' WHERE id=?", appointment);
        Map<String, Object> draft = Map.of("version", 0, "chiefComplaint", "牙痛", "examination", "需检查", "assessment", "待评估", "treatmentNote", "检查中", "advice", "观察", "items", List.of());
        ResponseEntity<Map> saved = http("PUT", "/doctor/appointments/" + appointment + "/medical-record/draft", doctor.token, null, draft);
        assertEquals(200, saved.getStatusCodeValue(), String.valueOf(saved.getBody()));
        long record = ((Number) saved.getBody().get("recordId")).longValue();
        assertTrue(((List<?>) http("GET", "/patient/medical-records", patient.token, null, null).getBody().get("items")).stream()
                .map(x -> (Map<?, ?>) x).noneMatch(x -> ((Number) x.get("id")).longValue() == record));
        assertEquals(404, http("GET", "/medical-records/" + record, patient.token, null, null).getStatusCodeValue());
        assertEquals(409, http("PUT", "/doctor/appointments/" + appointment + "/medical-record/draft", doctor.token, null, draft).getStatusCodeValue());
        assertEquals(200, http("PUT", "/doctor/appointments/" + appointment + "/start", doctor.token, null, Map.of()).getStatusCodeValue());
        String key = UUID.randomUUID().toString();
        assertEquals(200, http("POST", "/doctor/appointments/" + appointment + "/medical-record/submit", doctor.token, key, Map.of("version", 1)).getStatusCodeValue());
        assertEquals(200, http("POST", "/doctor/appointments/" + appointment + "/medical-record/submit", doctor.token, key, Map.of("version", 1)).getStatusCodeValue());
        assertEquals(1, count("SELECT COUNT(*) FROM medical_record WHERE appointment_id=?", appointment));
        assertEquals(2, count("SELECT COUNT(*) FROM medical_record_version WHERE record_id=?", record));
        assertEquals(200, http("POST", "/doctor/medical-records/" + record + "/corrections", doctor.token, null,
                Map.of("version", 2, "reason", "检查后修正", "chiefComplaint", "牙痛", "examination", "已复查", "assessment", "轻微不适", "treatmentNote", "无需处理", "advice", "按时复诊")).getStatusCodeValue());
        assertEquals(3, count("SELECT COUNT(*) FROM medical_record_version WHERE record_id=?", record));
        assertEquals("已复查", db.queryForObject("SELECT examination FROM medical_record WHERE id=?", String.class, record));
    }

    @Test
    void reviewAppealHidesRatingAfterApproval() {
        Fixture f = fixture(1, future());
        User patient = user("PATIENT", 0), doctor = userForDoctor(f.doctorId), admin = user("ADMIN", 0);
        ResponseEntity<Map> created = http("POST", "/appointments", patient.token, UUID.randomUUID().toString(), Map.of("slotId", Long.toString(f.slotId)));
        long appointment = appointmentId(created);
        assertEquals(200, http("POST", "/bills/" + billId(created) + "/mock-pay", patient.token, UUID.randomUUID().toString(), Map.of()).getStatusCodeValue());
        db.update("UPDATE appointment SET status='COMPLETED' WHERE id=?", appointment);
        assertEquals(200, http("POST", "/appointments/" + appointment + "/review", patient.token, null,
                Map.of("serviceScore", 2, "communicationScore", 2, "overallScore", 2, "content", "测试评价", "anonymous", false)).getStatusCodeValue());
        long review = count("SELECT id FROM review WHERE appointment_id=?", appointment);
        assertEquals(200, http("POST", "/doctor/reviews/" + review + "/appeal", doctor.token, null, Map.of("reason", "事实有误")).getStatusCodeValue());
        assertEquals(409, http("POST", "/doctor/reviews/" + review + "/appeal", doctor.token, null, Map.of("reason", "再次申诉")).getStatusCodeValue());
        long appeal = count("SELECT id FROM review_appeal WHERE review_id=?", review);
        assertEquals(200, http("PUT", "/admin/review-appeals/" + appeal + "/decision", admin.token, null,
                Map.of("decision", "APPROVED", "decisionNote", "审核同意")).getStatusCodeValue());
        assertEquals(0, count("SELECT COUNT(*) FROM review WHERE id=? AND visibility='VISIBLE'", review));
        List<?> publicReviews = (List<?>) http("GET", "/doctors/" + f.doctorId, patient.token, null, null).getBody().get("reviews");
        assertTrue(publicReviews.isEmpty());
    }

    @Test
    void doctorContextShowsOnlyAssignedPatientAfterCheckIn() {
        Fixture f = fixture(1, future());
        User patient = user("PATIENT", 0), assigned = userForDoctor(f.doctorId), other = user("DOCTOR", f.departmentId);
        long appointment = appointmentId(http("POST", "/appointments", patient.token, UUID.randomUUID().toString(), Map.of("slotId", Long.toString(f.slotId))));
        assertEquals(200, http("PUT", "/patient/health-archive", patient.token, null,
                Map.of("version", 0, "allergyHistory", "测试过敏史", "pastHistory", "无", "note", "仅授权医生可见")).getStatusCodeValue());
        assertEquals(409, http("GET", "/doctor/appointments/" + appointment + "/context", assigned.token, null, null).getStatusCodeValue());
        db.update("UPDATE appointment SET status='CHECKED_IN' WHERE id=?", appointment);
        assertEquals(404, http("GET", "/doctor/appointments/" + appointment + "/context", other.token, null, null).getStatusCodeValue());
        ResponseEntity<Map> result = http("GET", "/doctor/appointments/" + appointment + "/context", assigned.token, null, null);
        assertEquals(200, result.getStatusCodeValue());
        assertEquals("测试过敏史", ((Map<?, ?>) result.getBody().get("healthArchive")).get("allergy_history"));
    }

    @Test
    void settingsValidateBoundsAndOptimisticVersion() {
        User admin = user("ADMIN", 0), patient = user("PATIENT", 0);
        assertEquals(403, http("GET", "/admin/settings", patient.token, null, null).getStatusCodeValue());
        List<Map> items = (List<Map>) http("GET", "/admin/settings", admin.token, null, null).getBody().get("items");
        Map setting = items.stream().filter(x -> "booking.windowDays".equals(x.get("config_key"))).findFirst().orElseThrow();
        int version = ((Number) setting.get("version")).intValue();
        assertEquals(400, http("PUT", "/admin/settings/booking.windowDays", admin.token, null,
                Map.of("value", 99, "version", version, "reason", "测试越界")).getStatusCodeValue());
        assertEquals(200, http("PUT", "/admin/settings/booking.windowDays", admin.token, null,
                Map.of("value", 7, "version", version, "reason", "保持默认值")).getStatusCodeValue());
        assertEquals(409, http("PUT", "/admin/settings/booking.windowDays", admin.token, null,
                Map.of("value", 7, "version", version, "reason", "过期版本")).getStatusCodeValue());
    }
}
