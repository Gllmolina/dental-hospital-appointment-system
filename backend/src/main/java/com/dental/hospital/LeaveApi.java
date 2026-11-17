package com.dental.hospital;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api")
public class LeaveApi {
    private final JdbcTemplate db;
    private final Api api;

    public LeaveApi(JdbcTemplate db, Api api) {
        this.db = db;
        this.api = api;
    }

    private LocalDateTime timestamp(Map<String, Object> body, String key) {
        try {
            return OffsetDateTime.parse(Objects.toString(body.get(key), "")).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + "须包含时区偏移");
        }
    }

    private String text(Map<String, Object> body, String key, int max) {
        String value = Objects.toString(body.get(key), "").trim();
        if (value.isBlank() || value.length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + "须为1至" + max + "字");
        return value;
    }

    @GetMapping("/doctor/leave-requests")
    public Map<String, Object> mine(HttpServletRequest req) {
        Api.Actor actor = api.actor(req, "DOCTOR");
        return Map.of("code", 0, "items", db.queryForList("SELECT id,starts_at,ends_at,reason,status,review_note,reviewed_at,created_at FROM leave_request WHERE doctor_id=? ORDER BY id DESC", actor.profileId()));
    }

    @PostMapping("/doctor/leave-requests")
    public Map<String, Object> request(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        Api.Actor actor = api.actor(req, "DOCTOR");
        LocalDateTime start = timestamp(body, "startsAt"), end = timestamp(body, "endsAt");
        if (!end.isAfter(start) || !start.isAfter(LocalDateTime.now(ZoneOffset.UTC)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请假时段须从未来开始且结束晚于开始");
        String reason = text(body, "reason", 500);
        db.update("INSERT INTO leave_request(doctor_id,starts_at,ends_at,reason) VALUES(?,?,?,?)", actor.profileId(), start, end, reason);
        return Map.of("code", 0);
    }

    @GetMapping("/admin/leave-requests")
    public Map<String, Object> list(HttpServletRequest req, @RequestParam(required = false) String status) {
        api.actor(req, "ADMIN");
        List<Map<String, Object>> items = status == null || status.isBlank()
                ? db.queryForList("SELECT l.*,d.name doctor_name FROM leave_request l JOIN doctor_profile d ON d.id=l.doctor_id ORDER BY l.id DESC LIMIT 200")
                : db.queryForList("SELECT l.*,d.name doctor_name FROM leave_request l JOIN doctor_profile d ON d.id=l.doctor_id WHERE l.status=? ORDER BY l.id DESC LIMIT 200", status);
        return Map.of("code", 0, "items", items);
    }

    @PutMapping("/admin/leave-requests/{id}/decision")
    @Transactional
    public Map<String, Object> decide(HttpServletRequest req, @PathVariable long id, @RequestBody Map<String, Object> body) {
        Api.Actor actor = api.actor(req, "ADMIN");
        String decision = text(body, "decision", 20).toUpperCase();
        if (!decision.equals("APPROVED") && !decision.equals("REJECTED"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decision须为APPROVED或REJECTED");
        String note = Objects.toString(body.get("reviewNote"), "").trim();
        if (note.length() > 500 || decision.equals("REJECTED") && note.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "拒绝时须填写500字以内的审核说明");
        List<Map<String, Object>> initial = db.queryForList("SELECT doctor_id FROM leave_request WHERE id=?", id);
        if (initial.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "请假申请不存在");
        db.queryForList("SELECT id FROM doctor_profile WHERE id=? FOR UPDATE", initial.get(0).get("doctor_id"));
        List<Map<String, Object>> found = db.queryForList("SELECT * FROM leave_request WHERE id=? FOR UPDATE", id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "请假申请不存在");
        Map<String, Object> leave = found.get(0);
        if (!"PENDING".equals(leave.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "申请已处理");
        if (decision.equals("APPROVED")) {
            Long overlap = db.queryForObject("SELECT COUNT(*) FROM schedule WHERE doctor_id=? AND status='PUBLISHED' AND starts_at<? AND ends_at>?", Long.class,
                    leave.get("doctor_id"), leave.get("ends_at"), leave.get("starts_at"));
            if (overlap != null && overlap > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "请先停诊或调整重叠的已发布排班");
        }
        db.update("UPDATE leave_request SET status=?,reviewed_by=?,review_note=?,reviewed_at=UTC_TIMESTAMP(3) WHERE id=?", decision, actor.id(), note, id);
        db.update("INSERT INTO admin_operation_log(operator_id,action,target_type,target_id,reason) VALUES(?,'LEAVE_DECISION','LEAVE_REQUEST',?,?)", actor.id(), id, note);
        return Map.of("code", 0, "leaveRequestId", id, "status", decision);
    }
}
