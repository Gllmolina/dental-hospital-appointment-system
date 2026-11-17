package com.dental.hospital;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

@RestController
@RequestMapping("/api")
public class VerificationApi {
    private static final String ACCEPTED = "若账号存在，验证码已通过配置的通道发送";
    private final JdbcTemplate db;
    private final Api api;
    private final VerificationSender sender;
    private final TransactionTemplate transactions;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();
    private final byte[] secret;
    private final String mode;

    public VerificationApi(JdbcTemplate db, Api api, VerificationSender sender, TransactionTemplate transactions,
                           @Value("${VERIFICATION_SECRET:}") String secret,
                           @Value("${VERIFICATION_DELIVERY_MODE:disabled}") String mode) {
        this.db = db;
        this.api = api;
        this.sender = sender;
        this.transactions = transactions;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.mode = mode;
    }

    private void available(HttpServletRequest request) {
        if (!"local-file".equals(mode) || secret.length < 32)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "验证码发送通道未配置");
        try {
            if (!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress())
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "本地模拟发送仅允许本机访问");
        } catch (java.net.UnknownHostException error) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请求来源无效");
        }
    }

    private String field(Map<String, Object> body, String key, int max) {
        String value = Objects.toString(body.get(key), "").trim();
        if (value.isBlank() || value.length() > max)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + "无效");
        return value;
    }

    private String password(Map<String, Object> body, String key) {
        String value = field(body, key, 64);
        if (value.length() < 8 || value.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码须为8至64字符且不超过72字节");
        return value;
    }

    private String digest(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Verification HMAC unavailable", error);
        }
    }

    private boolean guard(String scope, int limit) {
        String key = digest("guard|" + scope);
        db.update("INSERT IGNORE INTO verification_rate_guard(guard_key,window_started_at,attempts) VALUES(?,UTC_TIMESTAMP(3),0)", key);
        Map<String, Object> current = db.queryForMap("SELECT window_started_at,attempts FROM verification_rate_guard WHERE guard_key=? FOR UPDATE", key);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Object raw = current.get("window_started_at");
        LocalDateTime started = raw instanceof LocalDateTime time ? time : ((Timestamp) raw).toLocalDateTime();
        int attempts = ((Number) current.get("attempts")).intValue();
        if (started.isBefore(now.minusMinutes(10))) {
            db.update("UPDATE verification_rate_guard SET window_started_at=UTC_TIMESTAMP(3),attempts=1 WHERE guard_key=?", key);
            return true;
        }
        if (attempts >= limit) return false;
        db.update("UPDATE verification_rate_guard SET attempts=attempts+1 WHERE guard_key=?", key);
        return true;
    }

    private void issue(long user, String purpose, String destination) {
        db.update("UPDATE verification_challenge SET used_at=UTC_TIMESTAMP(3) WHERE user_id=? AND purpose=? AND used_at IS NULL", user, purpose);
        String code = String.format(Locale.ROOT, "%08d", random.nextInt(100_000_000));
        db.update("INSERT INTO verification_challenge(user_id,purpose,destination,code_digest,expires_at) VALUES(?,?,?,?,UTC_TIMESTAMP(3)+INTERVAL 5 MINUTE)",
                user, purpose, destination, digest(purpose + "|" + user + "|" + destination + "|" + code));
        sender.send(purpose, destination, code);
    }

    private boolean consume(long user, String purpose, String destination, String code, boolean extraValid) {
        List<Map<String, Object>> found = db.queryForList("SELECT id,code_digest,expires_at,attempt_count FROM verification_challenge WHERE user_id=? AND purpose=? AND destination=? AND used_at IS NULL ORDER BY id DESC LIMIT 1 FOR UPDATE",
                user, purpose, destination);
        if (found.isEmpty()) return false;
        Map<String, Object> challenge = found.get(0);
        Object raw = challenge.get("expires_at");
        LocalDateTime expiry = raw instanceof LocalDateTime time ? time : ((Timestamp) raw).toLocalDateTime();
        int attempts = ((Number) challenge.get("attempt_count")).intValue();
        if (attempts >= 5 || !expiry.isAfter(LocalDateTime.now(ZoneOffset.UTC))) return false;
        byte[] expected = HexFormat.of().parseHex((String) challenge.get("code_digest"));
        byte[] actual = HexFormat.of().parseHex(digest(purpose + "|" + user + "|" + destination + "|" + code));
        if (!extraValid || !MessageDigest.isEqual(expected, actual)) {
            db.update("UPDATE verification_challenge SET attempt_count=attempt_count+1 WHERE id=?", challenge.get("id"));
            return false;
        }
        db.update("UPDATE verification_challenge SET used_at=UTC_TIMESTAMP(3) WHERE id=?", challenge.get("id"));
        return true;
    }

    private String code(Map<String, Object> body) {
        String code = field(body, "code", 8);
        if (!code.matches("[0-9]{8}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证码无效或已过期");
        return code;
    }

    private Map<String, Object> accepted() {
        return Map.of("code", 0, "message", ACCEPTED);
    }

    @PostMapping("/auth/password-reset/request")
    @Transactional
    public Map<String, Object> requestReset(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        available(request);
        long start = System.nanoTime();
        String identifier = field(body, "identifier", 64).toLowerCase(Locale.ROOT);
        boolean allowed = guard("reset-ip|" + request.getRemoteAddr(), 20)
                && guard("reset-account|" + identifier + "|" + request.getRemoteAddr(), 3);
        if (allowed) {
            List<Map<String, Object>> users = db.queryForList("SELECT id,phone FROM sys_user WHERE (username=? OR phone=?) AND status='ACTIVE'", identifier, identifier);
            if (!users.isEmpty()) {
                Map<String, Object> user = users.get(0);
                long id = ((Number) user.get("id")).longValue();
                db.queryForList("SELECT id FROM sys_user WHERE id=? FOR UPDATE", id);
                issue(id, "PASSWORD_RESET", (String) user.get("phone"));
            } else digest("dummy|" + identifier);
        }
        LockSupport.parkNanos(Math.max(0, 100_000_000L - (System.nanoTime() - start)));
        return accepted();
    }

    @PostMapping("/auth/password-reset/confirm")
    public Map<String, Object> confirmReset(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        available(request);
        String identifier = field(body, "identifier", 64).toLowerCase(Locale.ROOT);
        String code = code(body), next = password(body, "newPassword");
        boolean changed = Boolean.TRUE.equals(transactions.execute(status -> {
            List<Map<String, Object>> users = db.queryForList("SELECT id,phone FROM sys_user WHERE (username=? OR phone=?) AND status='ACTIVE' FOR UPDATE", identifier, identifier);
            if (users.isEmpty()) return false;
            Map<String, Object> user = users.get(0);
            long id = ((Number) user.get("id")).longValue();
            if (!consume(id, "PASSWORD_RESET", (String) user.get("phone"), code, true)) return false;
            db.update("UPDATE sys_user SET password_hash=?,must_change_password=FALSE WHERE id=?", bcrypt.encode(next), id);
            db.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP(3) WHERE user_id=? AND revoked_at IS NULL", id);
            return true;
        }));
        if (!changed) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证码无效或已过期");
        return Map.of("code", 0, "message", "密码已重置，请重新登录");
    }

    @PostMapping("/patient/phone-change/request")
    @Transactional
    public Map<String, Object> requestPhone(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        available(request);
        Api.Actor actor = api.actor(request, "PATIENT");
        String newPhone = field(body, "newPhone", 11);
        if (!newPhone.matches("1[0-9]{10}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "手机号格式错误");
        Map<String, Object> user = db.queryForMap("SELECT phone FROM sys_user WHERE id=? FOR UPDATE", actor.id());
        if (newPhone.equals(user.get("phone")) ||
                !db.queryForList("SELECT id FROM sys_user WHERE phone=?", newPhone).isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该手机号不可使用");
        if (!guard("phone-ip|" + request.getRemoteAddr(), 20) ||
                !guard("phone-account|" + actor.id() + "|" + newPhone, 3))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后重试");
        issue(actor.id(), "PHONE_CHANGE", newPhone);
        return Map.of("code", 0, "message", "验证码已通过配置的通道发送");
    }

    @PostMapping("/patient/phone-change/confirm")
    public Map<String, Object> confirmPhone(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        available(request);
        Api.Actor actor = api.actor(request, "PATIENT");
        String newPhone = field(body, "newPhone", 11), code = code(body), password = field(body, "currentPassword", 64);
        if (!newPhone.matches("1[0-9]{10}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "手机号格式错误");
        boolean changed;
        try {
            changed = Boolean.TRUE.equals(transactions.execute(status -> {
                Map<String, Object> user = db.queryForMap("SELECT password_hash FROM sys_user WHERE id=? FOR UPDATE", actor.id());
                boolean passwordMatches = bcrypt.matches(password, (String) user.get("password_hash"));
                if (!consume(actor.id(), "PHONE_CHANGE", newPhone, code, passwordMatches)) return false;
                db.update("UPDATE sys_user SET phone=? WHERE id=?", newPhone, actor.id());
                db.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP(3) WHERE user_id=? AND revoked_at IS NULL", actor.id());
                return true;
            }));
        } catch (DuplicateKeyException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该手机号不可使用");
        }
        if (!changed) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证码或当前密码无效");
        return Map.of("code", 0, "message", "手机号已更新，请重新登录");
    }
}
