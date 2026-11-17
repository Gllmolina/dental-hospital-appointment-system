package com.dental.hospital;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/doctor")
public class RecordDraftApi {
    private final JdbcTemplate db;
    private final Api api;
    private final ObjectMapper json;

    public RecordDraftApi(JdbcTemplate db, Api api, ObjectMapper json) {
        this.db = db;
        this.api = api;
        this.json = json;
    }

    private Map<String, Object> row(String sql, Object... args) {
        List<Map<String, Object>> found = db.queryForList(sql, args);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
        return found.get(0);
    }

    private long insert(String sql, Object... args) {
        GeneratedKeyHolder holder = new GeneratedKeyHolder();
        db.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }

    private String text(Map<String, Object> body, String key, boolean required) {
        String value = Objects.toString(body.get(key), "").trim();
        if (value.length() > 2000 || required && value.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + "须为1至2000字");
        return value;
    }

    private Object date(Map<String, Object> body) {
        String raw = Objects.toString(body.get("followUpDate"), "").trim();
        if (raw.isBlank()) return null;
        try { return LocalDate.parse(raw); }
        catch (RuntimeException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "复诊日期无效"); }
    }

    private int version(Map<String, Object> body) {
        try {
            int value = Integer.parseInt(Objects.toString(body.get("version"), ""));
            if (value >= 0) return value;
        } catch (NumberFormatException ignored) { }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "version无效");
    }

    private void snapshot(long record, int version, Map<String, Object> body, long actor, String reason) {
        db.update("INSERT INTO medical_record_version(record_id,version,chief_complaint,examination,assessment,treatment_note,advice,follow_up_date,changed_by,reason) VALUES(?,?,?,?,?,?,?,?,?,?)",
                record, version, text(body, "chiefComplaint", false), text(body, "examination", false),
                text(body, "assessment", false), text(body, "treatmentNote", false), text(body, "advice", false), date(body), actor, reason);
    }

    @GetMapping("/appointments/{id}/medical-record/draft")
    public Map<String, Object> getDraft(HttpServletRequest req, @PathVariable long id) {
        Api.Actor actor = api.actor(req, "DOCTOR");
        row("SELECT id FROM appointment WHERE id=? AND doctor_id=?", id, actor.profileId());
        List<Map<String, Object>> found = db.queryForList("SELECT id,status,version,chief_complaint,examination,assessment,treatment_note,advice,follow_up_date,draft_items_json,updated_at FROM medical_record WHERE appointment_id=? AND doctor_id=?", id, actor.profileId());
        return Map.of("code", 0, "record", found.isEmpty() ? Map.of("version", 0, "status", "EMPTY") : found.get(0));
    }

    @PutMapping("/appointments/{id}/medical-record/draft")
    @Transactional
    public Map<String, Object> saveDraft(HttpServletRequest req, @PathVariable long id, @RequestBody Map<String, Object> body) {
        Api.Actor actor = api.actor(req, "DOCTOR");
        Map<String, Object> appointment = row("SELECT status FROM appointment WHERE id=? AND doctor_id=? FOR UPDATE", id, actor.profileId());
        if (!List.of("CHECKED_IN", "IN_VISIT").contains(appointment.get("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前预约不能保存草稿");
        int expected = version(body);
        String items;
        try { items = json.writeValueAsString(body.getOrDefault("items", List.of())); }
        catch (JsonProcessingException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收费项目无效"); }
        if (items.length() > 10000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收费项目过多");
        List<Map<String, Object>> found = db.queryForList("SELECT id,status,version FROM medical_record WHERE appointment_id=? FOR UPDATE", id);
        long record;
        int next;
        if (found.isEmpty()) {
            if (expected != 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "草稿版本已变化");
            record = insert("INSERT INTO medical_record(appointment_id,doctor_id,chief_complaint,examination,assessment,treatment_note,advice,follow_up_date,status,version,draft_items_json) VALUES(?,?,?,?,?,?,?,?,'DRAFT',1,?)",
                    id, actor.profileId(), text(body, "chiefComplaint", false), text(body, "examination", false), text(body, "assessment", false),
                    text(body, "treatmentNote", false), text(body, "advice", false), date(body), items);
            next = 1;
        } else {
            Map<String, Object> old = found.get(0);
            if (!"DRAFT".equals(old.get("status")) || ((Number) old.get("version")).intValue() != expected)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "草稿版本已变化");
            record = ((Number) old.get("id")).longValue();
            next = expected + 1;
            db.update("UPDATE medical_record SET chief_complaint=?,examination=?,assessment=?,treatment_note=?,advice=?,follow_up_date=?,draft_items_json=?,version=? WHERE id=?",
                    text(body, "chiefComplaint", false), text(body, "examination", false), text(body, "assessment", false),
                    text(body, "treatmentNote", false), text(body, "advice", false), date(body), items, next, record);
        }
        snapshot(record, next, body, actor.id(), "草稿保存");
        return Map.of("code", 0, "recordId", record, "version", next, "status", "DRAFT");
    }

    @PostMapping("/medical-records/{id}/corrections")
    @Transactional
    public Map<String, Object> correct(HttpServletRequest req, @PathVariable long id, @RequestBody Map<String, Object> body) {
        Api.Actor actor = api.actor(req, "DOCTOR");
        Map<String, Object> old = row("SELECT id,status,version FROM medical_record WHERE id=? AND doctor_id=? FOR UPDATE", id, actor.profileId());
        int expected = version(body);
        if (!"PUBLISHED".equals(old.get("status")) || ((Number) old.get("version")).intValue() != expected)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "记录版本已变化");
        String reason = Objects.toString(body.get("reason"), "").trim();
        if (reason.isBlank() || reason.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入1至500字修订原因");
        int next = expected + 1;
        db.update("UPDATE medical_record SET chief_complaint=?,examination=?,assessment=?,treatment_note=?,advice=?,follow_up_date=?,version=? WHERE id=?",
                text(body, "chiefComplaint", true), text(body, "examination", true), text(body, "assessment", false),
                text(body, "treatmentNote", false), text(body, "advice", true), date(body), next, id);
        snapshot(id, next, body, actor.id(), reason);
        return Map.of("code", 0, "recordId", id, "version", next);
    }
}
