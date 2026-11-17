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
public class ReviewModerationApi {
    private final JdbcTemplate db;
    private final Api api;

    public ReviewModerationApi(JdbcTemplate db, Api api) {
        this.db = db;
        this.api = api;
    }

    private Map<String, Object> row(String sql, Object... args) {
        List<Map<String, Object>> found = db.queryForList(sql, args);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
        return found.get(0);
    }

    private String required(Map<String, Object> body, String key) {
        String value = Objects.toString(body.get(key), "").trim();
        if (value.isBlank() || value.length() > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + "须为1至500字");
        return value;
    }

    @PostMapping("/doctor/reviews/{id}/appeal")
    @Transactional
    public Map<String, Object> appeal(HttpServletRequest req, @PathVariable long id, @RequestBody Map<String, Object> body) {
        Api.Actor actor = api.actor(req, "DOCTOR");
        row("SELECT id FROM review WHERE id=? AND doctor_id=?", id, actor.profileId());
        String reason = required(body, "reason");
        db.update("INSERT INTO review_appeal(review_id,doctor_id,reason) VALUES(?,?,?)", id, actor.profileId(), reason);
        return Map.of("code", 0, "reviewId", id);
    }

    @GetMapping("/admin/review-appeals")
    public Map<String, Object> appeals(HttpServletRequest req, @RequestParam(required = false) String status) {
        api.actor(req, "ADMIN");
        String sql = "SELECT ap.id,ap.review_id,ap.doctor_id,ap.reason,ap.status,ap.decision_note,ap.created_at,r.content,r.overall_score,r.visibility,d.name doctor_name FROM review_appeal ap JOIN review r ON r.id=ap.review_id JOIN doctor_profile d ON d.id=ap.doctor_id";
        return Map.of("code", 0, "items", status == null || status.isBlank()
                ? db.queryForList(sql + " ORDER BY ap.id DESC LIMIT 200")
                : db.queryForList(sql + " WHERE ap.status=? ORDER BY ap.id DESC LIMIT 200", status));
    }

    @PutMapping("/admin/review-appeals/{id}/decision")
    @Transactional
    public Map<String, Object> decision(HttpServletRequest req, @PathVariable long id, @RequestBody Map<String, Object> body) {
        Api.Actor actor = api.actor(req, "ADMIN");
        String decision = required(body, "decision").toUpperCase();
        if (!decision.equals("APPROVED") && !decision.equals("REJECTED"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decision须为APPROVED或REJECTED");
        String note = required(body, "decisionNote");
        Map<String, Object> appeal = row("SELECT review_id,status FROM review_appeal WHERE id=? FOR UPDATE", id);
        if (!"PENDING".equals(appeal.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "申诉已处理");
        db.update("UPDATE review_appeal SET status=?,decision_note=?,decided_by=?,decided_at=UTC_TIMESTAMP(3) WHERE id=?", decision, note, actor.id(), id);
        if (decision.equals("APPROVED")) db.update("UPDATE review SET visibility='HIDDEN' WHERE id=?", appeal.get("review_id"));
        db.update("INSERT INTO admin_operation_log(operator_id,action,target_type,target_id,reason) VALUES(?,'REVIEW_APPEAL_DECISION','REVIEW_APPEAL',?,?)", actor.id(), id, note);
        return Map.of("code", 0, "appealId", id, "status", decision);
    }

    @PutMapping("/admin/reviews/{id}/visibility")
    @Transactional
    public Map<String, Object> visibility(HttpServletRequest req, @PathVariable long id, @RequestBody Map<String, Object> body) {
        Api.Actor actor = api.actor(req, "ADMIN");
        String visibility = required(body, "visibility").toUpperCase();
        if (!visibility.equals("VISIBLE") && !visibility.equals("HIDDEN"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "visibility须为VISIBLE或HIDDEN");
        String reason = required(body, "reason");
        row("SELECT id FROM review WHERE id=? FOR UPDATE", id);
        db.update("UPDATE review SET visibility=? WHERE id=?", visibility, id);
        db.update("INSERT INTO admin_operation_log(operator_id,action,target_type,target_id,reason) VALUES(?,'REVIEW_VISIBILITY','REVIEW',?,?)", actor.id(), id, reason);
        return Map.of("code", 0, "reviewId", id, "visibility", visibility);
    }
}
