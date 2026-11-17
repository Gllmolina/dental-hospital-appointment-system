package com.dental.hospital;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api")
public class AssistantApi {
    private static final String DISCLAIMER = "信息仅供就医参考，不能替代医生诊断；急症请及时到院就诊。";
    private final JdbcTemplate db;
    private final Api api;

    public AssistantApi(JdbcTemplate db, Api api) {
        this.db = db;
        this.api = api;
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

    private String required(Map<String, Object> body, String key, int max) {
        String value = Objects.toString(body.get(key), "").trim();
        if (value.isBlank() || value.length() > max)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + "须为1至" + max + "字");
        return value;
    }

    private String keywords(Map<String, Object> body) {
        String value = Objects.toString(body.get("keywords"), "").trim();
        if (value.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "keywords超过500字");
        return value;
    }

    @GetMapping("/admin/knowledge-items")
    public Map<String, Object> knowledge(HttpServletRequest req) {
        api.actor(req, "ADMIN");
        return Map.of("code", 0, "items", db.queryForList("SELECT id,family_id,category,question,answer,keywords,status,version,approved_at FROM knowledge_item ORDER BY id DESC LIMIT 200"));
    }

    @PostMapping("/admin/knowledge-items")
    public Map<String, Object> addKnowledge(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        api.actor(req, "ADMIN");
        long id = insert("INSERT INTO knowledge_item(category,question,answer,keywords) VALUES(?,?,?,?)",
                required(body, "category", 80), required(body, "question", 500), required(body, "answer", 2000), keywords(body));
        db.update("UPDATE knowledge_item SET family_id=? WHERE id=?", id, id);
        return Map.of("code", 0, "id", id);
    }

    @PutMapping("/admin/knowledge-items/{id}")
    @Transactional
    public Map<String, Object> revise(HttpServletRequest req, @PathVariable long id, @RequestBody Map<String, Object> body) {
        api.actor(req, "ADMIN");
        Map<String, Object> old = row("SELECT * FROM knowledge_item WHERE id=? FOR UPDATE", id);
        if ("DRAFT".equals(old.get("status"))) {
            db.update("UPDATE knowledge_item SET category=?,question=?,answer=?,keywords=? WHERE id=?",
                    required(body, "category", 80), required(body, "question", 500), required(body, "answer", 2000), keywords(body), id);
            return Map.of("code", 0, "id", id, "version", old.get("version"));
        }
        long family = ((Number) old.get("family_id")).longValue();
        Integer version = db.queryForObject("SELECT COALESCE(MAX(version),0)+1 FROM knowledge_item WHERE family_id=?", Integer.class, family);
        long revised = insert("INSERT INTO knowledge_item(family_id,category,question,answer,keywords,version) VALUES(?,?,?,?,?,?)",
                family, required(body, "category", 80), required(body, "question", 500), required(body, "answer", 2000), keywords(body), version);
        return Map.of("code", 0, "id", revised, "version", version);
    }

    @PutMapping("/admin/knowledge-items/{id}/publication")
    @Transactional
    public Map<String, Object> publish(HttpServletRequest req, @PathVariable long id) {
        Api.Actor actor = api.actor(req, "ADMIN");
        Map<String, Object> item = row("SELECT family_id,status FROM knowledge_item WHERE id=? FOR UPDATE", id);
        if (!"DRAFT".equals(item.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "仅草稿可以发布");
        row("SELECT id FROM knowledge_item WHERE id=? FOR UPDATE", item.get("family_id"));
        db.update("UPDATE knowledge_item SET status='ARCHIVED' WHERE family_id=? AND status='PUBLISHED'", item.get("family_id"));
        db.update("UPDATE knowledge_item SET status='PUBLISHED',approved_by=?,approved_at=UTC_TIMESTAMP(3) WHERE id=?", actor.id(), id);
        db.update("INSERT INTO admin_operation_log(operator_id,action,target_type,target_id) VALUES(?,'KNOWLEDGE_PUBLISH','KNOWLEDGE_ITEM',?)", actor.id(), id);
        return Map.of("code", 0, "id", id, "status", "PUBLISHED");
    }

    @GetMapping("/ai/sessions")
    public Map<String, Object> sessions(HttpServletRequest req) {
        Api.Actor actor = api.actor(req, "PATIENT");
        return Map.of("code", 0, "items", db.queryForList("SELECT id,title,created_at FROM ai_session WHERE patient_id=? AND deleted_at IS NULL ORDER BY id DESC", actor.profileId()));
    }

    @PostMapping("/ai/sessions")
    public Map<String, Object> newSession(HttpServletRequest req, @RequestBody(required = false) Map<String, Object> body) {
        Api.Actor actor = api.actor(req, "PATIENT");
        String title = Objects.toString(body == null ? null : body.get("title"), "新会话").trim();
        if (title.isBlank() || title.length() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话标题无效");
        long id = insert("INSERT INTO ai_session(patient_id,title) VALUES(?,?)", actor.profileId(), title);
        return Map.of("code", 0, "id", id);
    }

    @GetMapping("/ai/sessions/{id}")
    public Map<String, Object> session(HttpServletRequest req, @PathVariable long id) {
        Api.Actor actor = api.actor(req, "PATIENT");
        Map<String, Object> session = row("SELECT id,title,created_at FROM ai_session WHERE id=? AND patient_id=? AND deleted_at IS NULL", id, actor.profileId());
        return Map.of("code", 0, "session", session, "messages", db.queryForList("SELECT id,role,content,source,knowledge_id,created_at FROM ai_message WHERE session_id=? ORDER BY id", id));
    }

    @DeleteMapping("/ai/sessions/{id}")
    public Map<String, Object> deleteSession(HttpServletRequest req, @PathVariable long id) {
        Api.Actor actor = api.actor(req, "PATIENT");
        db.update("UPDATE ai_session SET deleted_at=COALESCE(deleted_at,UTC_TIMESTAMP(3)) WHERE id=? AND patient_id=?", id, actor.profileId());
        return Map.of("code", 0);
    }

    @PostMapping("/ai/chat")
    @Transactional
    public Map<String, Object> chat(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        Api.Actor actor = api.actor(req, "PATIENT");
        long sessionId;
        try { sessionId = Long.parseLong(required(body, "sessionId", 20)); }
        catch (NumberFormatException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId无效"); }
        row("SELECT id FROM ai_session WHERE id=? AND patient_id=? AND deleted_at IS NULL", sessionId, actor.profileId());
        String question = required(body, "question", 500);
        row("SELECT id FROM patient_profile WHERE id=? FOR UPDATE", actor.profileId());
        Long recent = db.queryForObject("SELECT COUNT(*) FROM ai_message m JOIN ai_session s ON s.id=m.session_id WHERE s.patient_id=? AND m.role='USER' AND m.created_at>=UTC_TIMESTAMP(3)-INTERVAL 1 MINUTE", Long.class, actor.profileId());
        if (recent != null && recent >= 10) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "提问过于频繁，请稍后重试");
        Instant begin = Instant.now();
        String answer;
        String source;
        Long knowledgeId = null;
        if (question.matches(".*(大量出血|呼吸困难|面部肿胀|剧烈疼痛|高烧).*")) {
            answer = "如出现明显出血、呼吸困难、面部肿胀或其他急症，请及时到急诊或联系医院。";
            source = "SAFETY_NOTICE";
        } else {
            String normalized = question.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
            List<Map<String, Object>> knowledge = db.queryForList("SELECT id,question,answer,keywords FROM knowledge_item WHERE status='PUBLISHED' ORDER BY id");
            int best = 0;
            Map<String, Object> selected = null;
            for (Map<String, Object> item : knowledge) {
                String canonical = Objects.toString(item.get("question"), "").toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
                int score = canonical.equals(normalized) ? 1000 : 0;
                int hits = 0;
                for (String token : Objects.toString(item.get("keywords"), "").toLowerCase(Locale.ROOT).split("[,，\\s]+"))
                    if (!token.isBlank() && normalized.contains(token)) hits++;
                if (hits >= 2) score = Math.max(score, hits);
                if (score > best) { best = score; selected = item; }
            }
            if (selected != null) {
                answer = Objects.toString(selected.get("answer"));
                source = "FAQ";
                knowledgeId = ((Number) selected.get("id")).longValue();
            } else {
                answer = "暂未找到经过审核的对应答案。请联系医院人工咨询，或在就诊时向医生说明症状。";
                source = "FALLBACK";
            }
        }
        db.update("INSERT INTO ai_message(session_id,role,content) VALUES(?,'USER',?)", sessionId, question);
        long messageId = insert("INSERT INTO ai_message(session_id,role,content,source,knowledge_id,elapsed_ms) VALUES(?,'ASSISTANT',?,?,?,?)",
                sessionId, answer, source, knowledgeId, (int) Duration.between(begin, Instant.now()).toMillis());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("messageId", messageId);
        result.put("answer", answer);
        result.put("source", source);
        result.put("knowledgeIds", knowledgeId == null ? List.of() : List.of(knowledgeId));
        result.put("disclaimer", DISCLAIMER);
        return result;
    }

    @PostMapping("/ai/messages/{id}/feedback")
    public Map<String, Object> messageFeedback(HttpServletRequest req, @PathVariable long id, @RequestBody Map<String, Object> body) {
        Api.Actor actor = api.actor(req, "PATIENT");
        row("SELECT m.id FROM ai_message m JOIN ai_session s ON s.id=m.session_id WHERE m.id=? AND m.role='ASSISTANT' AND s.patient_id=? AND s.deleted_at IS NULL", id, actor.profileId());
        Object helpful = body.get("helpful");
        if (!(helpful instanceof Boolean)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "helpful无效");
        db.update("INSERT INTO ai_feedback(patient_id,message_id,helpful) VALUES(?,?,?) ON DUPLICATE KEY UPDATE helpful=VALUES(helpful)", actor.profileId(), id, helpful);
        return Map.of("code", 0);
    }
}
