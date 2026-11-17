package com.dental.hospital;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@RestController
@RequestMapping("/api/recommend")
public class RecommendationApi {
    private static final ZoneId HOSPITAL = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate db;
    private final Api api;

    public RecommendationApi(JdbcTemplate db, Api api) {
        this.db = db;
        this.api = api;
    }

    private LocalDateTime dateTime(Object value) {
        return value instanceof LocalDateTime time ? time : ((java.sql.Timestamp) value).toLocalDateTime();
    }

    @GetMapping("/doctors")
    public Map<String, Object> doctors(HttpServletRequest req,
                                       @RequestParam(required = false) Long departmentId,
                                       @RequestParam(required = false) String tag,
                                       @RequestParam(defaultValue = "5") int limit) {
        Api.Actor actor = api.actor(req, "PATIENT");
        if (limit < 1 || limit > 10) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit须为1至10");
        String selectedTag = tag == null ? "" : tag.trim().toLowerCase(Locale.ROOT);
        if (selectedTag.length() > 60) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标签过长");
        LocalDate today = LocalDate.now(HOSPITAL);
        List<Map<String, Object>> candidates = db.queryForList("""
                SELECT d.id,d.name,d.title,d.specialty,d.department_id,dep.name department_name,
                       COUNT(DISTINCT sl.id) open_slots,MIN(sl.starts_at) next_available_at,
                       (SELECT COUNT(*) FROM review r WHERE r.doctor_id=d.id AND r.visibility='VISIBLE') review_count,
                       (SELECT AVG(r.overall_score) FROM review r WHERE r.doctor_id=d.id AND r.visibility='VISIBLE') rating_avg
                FROM doctor_profile d JOIN sys_user u ON u.id=d.user_id JOIN department dep ON dep.id=d.department_id
                JOIN schedule s ON s.doctor_id=d.id AND s.status='PUBLISHED'
                JOIN schedule_slot sl ON sl.schedule_id=s.id AND sl.status='OPEN' AND sl.remaining>0 AND sl.starts_at>UTC_TIMESTAMP(3)
                WHERE d.status='ACTIVE' AND u.status='ACTIVE' AND dep.enabled=TRUE
                  AND s.visit_date>=? AND s.visit_date<?
                GROUP BY d.id,d.name,d.title,d.specialty,d.department_id,dep.name
                """, today, today.plusDays(7));
        List<Map<String, Object>> history = db.queryForList("SELECT doctor_id,COUNT(*) visits FROM appointment WHERE patient_id=? AND status='COMPLETED' AND completed_at>=UTC_TIMESTAMP(3)-INTERVAL 180 DAY GROUP BY doctor_id", actor.profileId());
        Map<Long, Long> visits = new HashMap<>();
        long totalVisits = 0;
        for (Map<String, Object> h : history) {
            long doctor = ((Number) h.get("doctor_id")).longValue();
            long count = ((Number) h.get("visits")).longValue();
            visits.put(doctor, count);
            totalVisits += count;
        }
        List<Map<String, Object>> ranked = new ArrayList<>();
        for (Map<String, Object> doctor : candidates) {
            long id = ((Number) doctor.get("id")).longValue();
            long dep = ((Number) doctor.get("department_id")).longValue();
            if (departmentId != null && dep != departmentId) continue;
            boolean tagMatched = !selectedTag.isBlank() && Objects.toString(doctor.get("specialty"), "").toLowerCase(Locale.ROOT).contains(selectedTag);
            double match = selectedTag.isBlank() ? 1 : tagMatched ? 1 : departmentId != null ? 0.7 : 0;
            double available = Math.min(((Number) doctor.get("open_slots")).intValue(), 10) / 10.0;
            int reviewCount = ((Number) doctor.get("review_count")).intValue();
            double rating = reviewCount >= 3 ? ((Number) doctor.get("rating_avg")).doubleValue() / 5.0 : 0.6;
            double historyScore = totalVisits == 0 ? 0 : visits.getOrDefault(id, 0L) / (double) totalVisits;
            double denominator = 0.25 + 0.20 + (departmentId != null || !selectedTag.isBlank() ? 0.40 : 0) + (totalVisits > 0 ? 0.15 : 0);
            double score = (available * 0.25 + rating * 0.20 + (departmentId != null || !selectedTag.isBlank() ? match * 0.40 : 0) + historyScore * 0.15) / denominator;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("doctorId", id);
            result.put("name", doctor.get("name"));
            result.put("departmentName", doctor.get("department_name"));
            result.put("score", Math.round(score * 1000.0) / 1000.0);
            result.put("components", Map.of("match", match, "availability", available, "rating", rating, "history", historyScore));
            result.put("reasons", List.of("近7天有" + doctor.get("open_slots") + "个可约时段", reviewCount >= 3 ? "有效评价" + reviewCount + "条" : "评价不足3条，采用中性评分"));
            result.put("nextAvailableAt", doctor.get("next_available_at"));
            ranked.add(result);
        }
        ranked.sort(Comparator.comparingDouble((Map<String, Object> item) -> ((Number) item.get("score")).doubleValue()).reversed()
                .thenComparing(item -> dateTime(item.get("nextAvailableAt")))
                .thenComparingLong(item -> ((Number) item.get("doctorId")).longValue()));
        return Map.of("code", 0, "items", ranked.subList(0, Math.min(limit, ranked.size())));
    }
}
