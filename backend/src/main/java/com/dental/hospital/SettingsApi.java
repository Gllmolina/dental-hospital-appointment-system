package com.dental.hospital;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/admin/settings")
public class SettingsApi {
    private static final Map<String, int[]> BOUNDS = Map.of(
            "booking.windowDays", new int[]{1, 30},
            "booking.dailyLimit", new int[]{1, 10},
            "booking.holdMinutes", new int[]{1, 60},
            "booking.cancelBeforeMinutes", new int[]{0, 1440},
            "booking.checkInEarlyMinutes", new int[]{0, 120},
            "schedule.slotMinutes", new int[]{5, 120}
    );
    private final JdbcTemplate db;
    private final Api api;

    public SettingsApi(JdbcTemplate db, Api api) {
        this.db = db;
        this.api = api;
    }

    @GetMapping
    public Map<String, Object> list(HttpServletRequest req) {
        api.actor(req, "ADMIN");
        return Map.of("code", 0, "items", db.queryForList("SELECT config_key,config_value,version,updated_at FROM system_config ORDER BY config_key"));
    }

    @PutMapping("/{key}")
    @Transactional
    public Map<String, Object> update(HttpServletRequest req, @PathVariable String key, @RequestBody Map<String, Object> body) {
        Api.Actor actor = api.actor(req, "ADMIN");
        int[] bounds = BOUNDS.get(key);
        if (bounds == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "配置项不存在");
        int value, version;
        try {
            value = Integer.parseInt(Objects.toString(body.get("value"), ""));
            version = Integer.parseInt(Objects.toString(body.get("version"), ""));
        } catch (NumberFormatException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "配置值或版本无效");
        }
        if (value < bounds[0] || value > bounds[1] || key.equals("schedule.slotMinutes") && 30 % value != 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "配置值超出允许范围");
        String reason = Objects.toString(body.get("reason"), "").trim();
        if (reason.isBlank() || reason.length() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入1至100字修改原因");
        int changed = db.update("UPDATE system_config SET config_value=?,version=version+1,updated_by=? WHERE config_key=? AND version=?",
                Integer.toString(value), actor.id(), key, version);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "配置版本已变化");
        db.update("INSERT INTO admin_operation_log(operator_id,action,target_type,target_id,reason) VALUES(?,'SETTING_UPDATE','SYSTEM_CONFIG',0,?)", actor.id(), reason + "：" + key);
        return Map.of("code", 0, "key", key, "value", value, "version", version + 1);
    }
}
