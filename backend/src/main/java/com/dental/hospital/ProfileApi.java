package com.dental.hospital;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api")
public class ProfileApi {
    private final JdbcTemplate db;
    private final Api api;

    public ProfileApi(JdbcTemplate db, Api api) {
        this.db = db;
        this.api = api;
    }

    private Map<String, Object> row(String sql, Object... args) {
        List<Map<String, Object>> found = db.queryForList(sql, args);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
        return found.get(0);
    }

    private String text(Map<String, Object> body, String key, int max, boolean required) {
        String value = Objects.toString(body.get(key), "").trim();
        if (value.length() > max || required && value.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + "长度无效");
        return value;
    }

    @GetMapping("/patient/profile")
    public Map<String, Object> patient(HttpServletRequest req) {
        Api.Actor actor = api.actor(req, "PATIENT");
        return Map.of("code", 0, "profile", row("SELECT p.id,p.name,p.gender,p.birthday,p.avatar_key,u.username,u.phone FROM patient_profile p JOIN sys_user u ON u.id=p.user_id WHERE p.id=?", actor.profileId()));
    }

    @PutMapping("/patient/profile")
    @Transactional
    public Map<String, Object> updatePatient(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        Api.Actor actor = api.actor(req, "PATIENT");
        String name = text(body, "name", 30, true), gender = text(body, "gender", 16, false);
        if (!gender.isBlank() && !List.of("MALE", "FEMALE", "OTHER").contains(gender))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "gender无效");
        String birthdayText = text(body, "birthday", 10, false);
        LocalDate birthday = null;
        if (!birthdayText.isBlank()) {
            try { birthday = LocalDate.parse(birthdayText); }
            catch (RuntimeException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "生日格式无效"); }
            if (birthday.isAfter(LocalDate.now())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "生日不能晚于今天");
        }
        db.update("UPDATE patient_profile SET name=?,gender=?,birthday=? WHERE id=?", name, gender.isBlank() ? null : gender, birthday, actor.profileId());
        return patient(req);
    }

    @GetMapping("/doctor/profile")
    public Map<String, Object> doctor(HttpServletRequest req) {
        Api.Actor actor = api.actor(req, "DOCTOR");
        return Map.of("code", 0, "profile", row("SELECT d.id,d.name,d.employee_no,d.title,d.specialty,d.intro,d.default_fee,d.avatar_key,dep.name department_name,u.username FROM doctor_profile d JOIN department dep ON dep.id=d.department_id JOIN sys_user u ON u.id=d.user_id WHERE d.id=?", actor.profileId()));
    }

    @PutMapping("/doctor/profile")
    public Map<String, Object> updateDoctor(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        Api.Actor actor = api.actor(req, "DOCTOR");
        String specialty = text(body, "specialty", 500, false), intro = text(body, "intro", 1000, false);
        db.update("UPDATE doctor_profile SET specialty=?,intro=? WHERE id=?", specialty, intro, actor.profileId());
        return doctor(req);
    }

    @GetMapping("/doctor/appointments/{id}/context")
    public Map<String, Object> context(HttpServletRequest req, @PathVariable long id) {
        Api.Actor actor = api.actor(req, "DOCTOR");
        Map<String, Object> appointment = row("SELECT a.id,a.patient_id,a.status,p.name,p.gender,p.birthday FROM appointment a JOIN patient_profile p ON p.id=a.patient_id WHERE a.id=? AND a.doctor_id=?", id, actor.profileId());
        if (!List.of("CHECKED_IN", "IN_VISIT").contains(appointment.get("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前预约不可查看接诊资料");
        List<Map<String, Object>> archives = db.queryForList("SELECT allergy_history,past_history,note FROM health_archive WHERE patient_id=?", appointment.get("patient_id"));
        List<Map<String, Object>> prior = db.queryForList("SELECT r.id,r.chief_complaint,r.examination,r.assessment,r.treatment_note,r.advice,r.follow_up_date,r.published_at FROM medical_record r JOIN appointment a ON a.id=r.appointment_id WHERE a.patient_id=? AND a.doctor_id=? AND a.id<>? AND r.status='PUBLISHED' ORDER BY r.published_at DESC LIMIT 10", appointment.get("patient_id"), actor.profileId(), id);
        return Map.of("code", 0, "patient", Map.of("name", appointment.get("name"), "gender", Objects.toString(appointment.get("gender"), ""), "birthday", Objects.toString(appointment.get("birthday"), "")),
                "healthArchive", archives.isEmpty() ? Map.of() : archives.get(0), "priorRecords", prior);
    }
}
