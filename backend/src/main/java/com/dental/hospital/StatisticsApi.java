package com.dental.hospital;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/statistics")
public class StatisticsApi {
    private final JdbcTemplate db;
    private final Api api;

    public StatisticsApi(JdbcTemplate db, Api api) {
        this.db = db;
        this.api = api;
    }

    private long number(String sql, Object... args) {
        Long value = db.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private Map<String, Object> ratio(long numerator, long denominator) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("numerator", numerator);
        result.put("denominator", denominator);
        result.put("value", denominator == 0 ? null : Math.round(1000.0 * numerator / denominator) / 1000.0);
        return result;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(HttpServletRequest req,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                        @RequestParam(required = false) Long departmentId,
                                        @RequestParam(required = false) Long doctorId) {
        api.actor(req, "ADMIN");
        if (to.isBefore(from) || to.isAfter(from.plusDays(89)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期范围最多90天");
        String apFilter = " WHERE a.visit_date BETWEEN ? AND ? AND (? IS NULL OR a.department_id=?) AND (? IS NULL OR a.doctor_id=?) ";
        Object[] apArgs = {from, to, departmentId, departmentId, doctorId, doctorId};
        long total = number("SELECT COUNT(*) FROM appointment a" + apFilter, apArgs);
        long completed = number("SELECT COUNT(*) FROM appointment a" + apFilter + " AND a.status='COMPLETED'", apArgs);
        long cancelled = number("SELECT COUNT(*) FROM appointment a" + apFilter + " AND a.status='CANCELLED'", apArgs);
        long expired = number("SELECT COUNT(*) FROM appointment a" + apFilter + " AND a.status='EXPIRED'", apArgs);

        String active = " FROM appointment a JOIN schedule_slot sl ON sl.id=a.slot_id JOIN schedule s ON s.id=sl.schedule_id" + apFilter + " AND s.status='PUBLISHED' ";
        long noShow = number("SELECT COUNT(*)" + active + " AND a.status='NO_SHOW' AND a.ends_at<UTC_TIMESTAMP(3)", apArgs);
        long visits = number("SELECT COUNT(*)" + active + " AND a.status IN('NO_SHOW','COMPLETED') AND a.ends_at<UTC_TIMESTAMP(3)", apArgs);
        long occupied = number("SELECT COUNT(*)" + active + " AND a.status IN('BOOKED','CHECKED_IN','IN_VISIT','COMPLETED','NO_SHOW')", apArgs);
        Object[] slotArgs = {from, to, departmentId, departmentId, doctorId, doctorId};
        long capacity = number("SELECT COALESCE(SUM(sl.capacity),0) FROM schedule_slot sl JOIN schedule s ON s.id=sl.schedule_id WHERE s.status='PUBLISHED' AND s.visit_date BETWEEN ? AND ? AND (? IS NULL OR s.department_id=?) AND (? IS NULL OR s.doctor_id=?)", slotArgs);
        long repeat = number("SELECT COUNT(DISTINCT a.patient_id) FROM appointment a" + apFilter + " AND a.status='COMPLETED' AND EXISTS (SELECT 1 FROM appointment old WHERE old.patient_id=a.patient_id AND old.status='COMPLETED' AND old.completed_at<a.completed_at)", apArgs);

        List<Map<String, Object>> trends = db.queryForList("SELECT a.visit_date,a.department_id,COUNT(*) total,SUM(a.status='COMPLETED') completed,SUM(a.status='CANCELLED') cancelled FROM appointment a" + apFilter + " GROUP BY a.visit_date,a.department_id ORDER BY a.visit_date,a.department_id", apArgs);
        List<Map<String, Object>> distribution = db.queryForList("SELECT a.department_id,COUNT(*) total,SUM(a.status='COMPLETED') completed,SUM(a.status='CANCELLED') cancelled FROM appointment a" + apFilter + " GROUP BY a.department_id ORDER BY total DESC,a.department_id", apArgs);
        List<Map<String, Object>> heat = db.queryForList("SELECT HOUR(CONVERT_TZ(a.starts_at,'+00:00','+08:00')) visit_hour,COUNT(*) total FROM appointment a" + apFilter + " GROUP BY visit_hour ORDER BY visit_hour", apArgs);
        List<Map<String, Object>> workload = db.queryForList("""
                SELECT d.id doctor_id,d.name doctor_name,
                  (SELECT COUNT(*) FROM appointment a WHERE a.doctor_id=d.id AND a.status='COMPLETED' AND a.visit_date BETWEEN ? AND ?) completed,
                  (SELECT COALESCE(SUM(TIMESTAMPDIFF(MINUTE,s.starts_at,s.ends_at)),0) FROM schedule s WHERE s.doctor_id=d.id AND s.status='PUBLISHED' AND s.visit_date BETWEEN ? AND ?) published_minutes,
                  (SELECT ROUND(AVG(r.overall_score),1) FROM review r WHERE r.doctor_id=d.id AND r.visibility='VISIBLE') rating_average
                FROM doctor_profile d WHERE (? IS NULL OR d.department_id=?) AND (? IS NULL OR d.id=?)
                ORDER BY completed DESC,d.id
                """, from, to, from, to, departmentId, departmentId, doctorId, doctorId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("asOf", OffsetDateTime.now(ZoneOffset.UTC).toString());
        result.put("counts", Map.of("total", total, "completed", completed, "cancelled", cancelled, "expired", expired, "repeatPatients", repeat));
        result.put("cancellationRate", ratio(cancelled, total));
        result.put("noShowRate", ratio(noShow, visits));
        result.put("utilization", ratio(occupied, capacity));
        result.put("trends", trends);
        result.put("departmentDistribution", distribution);
        result.put("hourlyHeat", heat);
        result.put("doctorWorkload", workload);
        return result;
    }
}
