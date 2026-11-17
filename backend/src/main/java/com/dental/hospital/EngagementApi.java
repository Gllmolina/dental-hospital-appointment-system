package com.dental.hospital;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api")
public class EngagementApi {
    private final JdbcTemplate db;
    private final Api api;

    public EngagementApi(JdbcTemplate db, Api api) {
        this.db = db;
        this.api = api;
    }

    private Map<String, Object> row(String sql, Object... args) {
        List<Map<String, Object>> found = db.queryForList(sql, args);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
        return found.get(0);
    }

    private long count(String sql, Object... args) {
        Long result = db.queryForObject(sql, Long.class, args);
        return result == null ? 0 : result;
    }

    private String text(Map<String, Object> body, String key, int max) {
        String result = Objects.toString(body.get(key), "").trim();
        if (result.length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + "超过长度限制");
        return result;
    }

    private int integer(Map<String, Object> body, String key, int min, int max) {
        try {
            int result = Integer.parseInt(Objects.toString(body.get(key), ""));
            if (result >= min && result <= max) return result;
        } catch (NumberFormatException ignored) {
            // Report the same field error for missing and malformed input.
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + "无效");
    }

    private boolean flag(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        if (raw instanceof Boolean value) return value;
        if ("true".equals(raw)) return true;
        if ("false".equals(raw)) return false;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + "无效");
    }

    @GetMapping("/patient/health-archive")
    public Map<String, Object> archive(HttpServletRequest req) {
        Api.Actor actor = api.actor(req, "PATIENT");
        List<Map<String, Object>> data = db.queryForList("SELECT allergy_history,past_history,note,version,updated_at FROM health_archive WHERE patient_id=?", actor.profileId());
        if (data.isEmpty()) return Map.of("code", 0, "archive", Map.of("allergy_history", "", "past_history", "", "note", "", "version", 0));
        return Map.of("code", 0, "archive", data.get(0));
    }

    @PutMapping("/patient/health-archive")
    @Transactional
    public Map<String, Object> saveArchive(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        Api.Actor actor = api.actor(req, "PATIENT");
        int version = integer(body, "version", 0, Integer.MAX_VALUE);
        String allergy = text(body, "allergyHistory", 500), past = text(body, "pastHistory", 500), note = text(body, "note", 500);
        if (version == 0) {
            if (count("SELECT COUNT(*) FROM health_archive WHERE patient_id=?", actor.profileId()) != 0)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "档案已被更新，请刷新");
            db.update("INSERT INTO health_archive(patient_id,allergy_history,past_history,note) VALUES(?,?,?,?)", actor.profileId(), allergy, past, note);
        } else {
            int changed = db.update("UPDATE health_archive SET allergy_history=?,past_history=?,note=?,version=version+1 WHERE patient_id=? AND version=?", allergy, past, note, actor.profileId(), version);
            if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "档案已被更新，请刷新");
        }
        return archive(req);
    }

    @GetMapping("/patient/favorites")
    public Map<String, Object> favorites(HttpServletRequest req) {
        Api.Actor actor = api.actor(req, "PATIENT");
        return Map.of("code", 0, "items", db.queryForList("SELECT d.id doctor_id,d.name,d.title,d.specialty,dep.name department_name,f.created_at FROM favorite_doctor f JOIN doctor_profile d ON d.id=f.doctor_id JOIN department dep ON dep.id=d.department_id WHERE f.patient_id=? ORDER BY f.created_at DESC,d.id DESC", actor.profileId()));
    }

    @PutMapping("/patient/favorites/{doctorId}")
    public Map<String, Object> addFavorite(HttpServletRequest req, @PathVariable long doctorId) {
        Api.Actor actor = api.actor(req, "PATIENT");
        row("SELECT id FROM doctor_profile WHERE id=? AND status='ACTIVE'", doctorId);
        db.update("INSERT IGNORE INTO favorite_doctor(patient_id,doctor_id) VALUES(?,?)", actor.profileId(), doctorId);
        return Map.of("code", 0, "doctorId", doctorId, "favorite", true);
    }

    @DeleteMapping("/patient/favorites/{doctorId}")
    public Map<String, Object> removeFavorite(HttpServletRequest req, @PathVariable long doctorId) {
        Api.Actor actor = api.actor(req, "PATIENT");
        db.update("DELETE FROM favorite_doctor WHERE patient_id=? AND doctor_id=?", actor.profileId(), doctorId);
        return Map.of("code", 0, "doctorId", doctorId, "favorite", false);
    }

    @PostMapping("/appointments/{id}/review")
    @Transactional
    public Map<String, Object> review(HttpServletRequest req, @PathVariable long id, @RequestBody Map<String, Object> body) {
        Api.Actor actor = api.actor(req, "PATIENT");
        Map<String, Object> appointment = row("SELECT id,doctor_id,status FROM appointment WHERE id=? AND patient_id=? FOR UPDATE", id, actor.profileId());
        if (!"COMPLETED".equals(appointment.get("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅已完成就诊可评价");
        if (count("SELECT COUNT(*) FROM bill WHERE appointment_id=? AND status='UNPAID'", id) != 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先支付账单");
        int service = integer(body, "serviceScore", 1, 5);
        int communication = integer(body, "communicationScore", 1, 5);
        int overall = integer(body, "overallScore", 1, 5);
        String content = text(body, "content", 500);
        boolean anonymous = flag(body, "anonymous");
        db.update("INSERT INTO review(appointment_id,patient_id,doctor_id,service_score,communication_score,overall_score,content,anonymous) VALUES(?,?,?,?,?,?,?,?)",
                id, actor.profileId(), appointment.get("doctor_id"), service, communication, overall, content, anonymous);
        return Map.of("code", 0, "appointmentId", id);
    }

    @GetMapping("/patient/reviews")
    public Map<String, Object> patientReviews(HttpServletRequest req) {
        Api.Actor actor = api.actor(req, "PATIENT");
        return Map.of("code", 0, "items", db.queryForList("SELECT id,appointment_id,doctor_id,service_score,communication_score,overall_score,content,anonymous,visibility,reply,replied_at,created_at FROM review WHERE patient_id=? ORDER BY id DESC", actor.profileId()));
    }

    @GetMapping("/doctor/reviews")
    public Map<String, Object> doctorReviews(HttpServletRequest req) {
        Api.Actor actor = api.actor(req, "DOCTOR");
        return Map.of("code", 0, "items", db.queryForList("SELECT r.id,r.service_score,r.communication_score,r.overall_score,r.content,r.anonymous,r.visibility,r.reply,r.replied_at,r.created_at,ap.status appeal_status,CASE WHEN r.anonymous THEN '匿名患者' ELSE p.name END display_name FROM review r JOIN patient_profile p ON p.id=r.patient_id LEFT JOIN review_appeal ap ON ap.review_id=r.id WHERE r.doctor_id=? AND r.visibility='VISIBLE' ORDER BY r.id DESC", actor.profileId()));
    }

    @PostMapping("/doctor/reviews/{id}/reply")
    @Transactional
    public Map<String, Object> replyReview(HttpServletRequest req, @PathVariable long id, @RequestBody Map<String, Object> body) {
        Api.Actor actor = api.actor(req, "DOCTOR");
        String content = text(body, "content", 500);
        if (content.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入回复");
        int changed = db.update("UPDATE review SET reply=?,replied_at=UTC_TIMESTAMP(3) WHERE id=? AND doctor_id=? AND reply IS NULL", content, id, actor.profileId());
        if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "评价不存在或已回复");
        return Map.of("code", 0, "reviewId", id);
    }

    @PostMapping("/feedback")
    public Map<String, Object> feedback(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        Api.Actor actor = api.actor(req, "PATIENT");
        String type = text(body, "type", 32), content = text(body, "content", 1000);
        if (type.isBlank() || content.length() < 10) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择反馈类型并填写至少10字内容");
        db.update("INSERT INTO feedback(user_id,type,content) VALUES(?,?,?)", actor.id(), type, content);
        return Map.of("code", 0);
    }

    @GetMapping("/patient/feedback")
    public Map<String, Object> patientFeedback(HttpServletRequest req) {
        Api.Actor actor = api.actor(req, "PATIENT");
        return Map.of("code", 0, "items", db.queryForList("SELECT id,type,content,status,reply,replied_at,closed_at,created_at FROM feedback WHERE user_id=? ORDER BY id DESC", actor.id()));
    }

    @GetMapping("/admin/feedback")
    public Map<String, Object> adminFeedback(HttpServletRequest req, @RequestParam(required = false) String status) {
        api.actor(req, "ADMIN");
        if (status == null || status.isBlank())
            return Map.of("code", 0, "items", db.queryForList("SELECT id,user_id,type,content,status,reply,replied_at,closed_at,created_at FROM feedback ORDER BY id DESC LIMIT 200"));
        return Map.of("code", 0, "items", db.queryForList("SELECT id,user_id,type,content,status,reply,replied_at,closed_at,created_at FROM feedback WHERE status=? ORDER BY id DESC LIMIT 200", status));
    }

    @PutMapping("/admin/feedback/{id}/reply")
    @Transactional
    public Map<String, Object> replyFeedback(HttpServletRequest req, @PathVariable long id, @RequestBody Map<String, Object> body) {
        Api.Actor actor = api.actor(req, "ADMIN");
        String content = text(body, "reply", 1000);
        if (content.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入回复");
        Map<String, Object> feedback = row("SELECT user_id,status FROM feedback WHERE id=? FOR UPDATE", id);
        if ("CLOSED".equals(feedback.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "反馈已关闭");
        boolean first = feedback.get("status").equals("PENDING") || feedback.get("status").equals("PROCESSING");
        db.update("UPDATE feedback SET status='REPLIED',reply=?,replied_by=?,replied_at=UTC_TIMESTAMP(3) WHERE id=?", content, actor.id(), id);
        if (first) db.update("INSERT INTO system_message(receiver_id,appointment_id,type,title,content) VALUES(?,NULL,'FEEDBACK_REPLY','反馈已有回复',?)", feedback.get("user_id"), content.substring(0, Math.min(content.length(), 500)));
        db.update("INSERT INTO admin_operation_log(operator_id,action,target_type,target_id) VALUES(?,'FEEDBACK_REPLY','FEEDBACK',?)", actor.id(), id);
        return Map.of("code", 0, "feedbackId", id);
    }

    @PutMapping("/admin/feedback/{id}/close")
    @Transactional
    public Map<String, Object> closeFeedback(HttpServletRequest req, @PathVariable long id) {
        Api.Actor actor = api.actor(req, "ADMIN");
        int changed = db.update("UPDATE feedback SET status='CLOSED',closed_at=UTC_TIMESTAMP(3) WHERE id=? AND status='REPLIED'", id);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "仅已回复反馈可关闭");
        db.update("INSERT INTO admin_operation_log(operator_id,action,target_type,target_id) VALUES(?,'FEEDBACK_CLOSE','FEEDBACK',?)", actor.id(), id);
        return Map.of("code", 0, "feedbackId", id);
    }
}
