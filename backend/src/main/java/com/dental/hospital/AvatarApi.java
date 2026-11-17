package com.dental.hospital;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.servlet.http.HttpServletRequest;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class AvatarApi {
    private final Api api;
    private final JdbcTemplate db;
    private final Path directory;

    public AvatarApi(Api api, JdbcTemplate db, @Value("${AVATAR_DIR:}") String configured) {
        this.api = api;
        this.db = db;
        this.directory = configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".dental-hospital", "avatars")
                : Path.of(configured).toAbsolutePath().normalize();
    }

    private String table(String role) { return "DOCTOR".equals(role) ? "doctor_profile" : "patient_profile"; }

    private Path storage() throws Exception {
        Path home = Path.of(System.getProperty("user.home")).toRealPath();
        Files.createDirectories(directory);
        Path real = directory.toRealPath();
        if (!real.startsWith(home) || real.equals(home)) throw new IllegalStateException("Avatar storage must be under the application user's home");
        return real;
    }

    private byte[] normalized(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > 2_000_000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "头像须小于2 MB");
        try {
            byte[] source = file.getBytes();
            try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 JPEG、PNG、WebP 图片");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(input);
                    String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                    if (!java.util.Set.of("jpeg", "jpg", "png", "webp").contains(format))
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 JPEG、PNG、WebP 图片");
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 32 || height < 32 || width > 2048 || height > 2048)
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "头像尺寸须在32至2048像素之间");
                    BufferedImage decoded = reader.read(0);
                    if (decoded == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片无法解码");
                    double scale = Math.min(1.0, 512.0 / Math.max(width, height));
                    int targetWidth = Math.max(1, (int) Math.round(width * scale));
                    int targetHeight = Math.max(1, (int) Math.round(height * scale));
                    BufferedImage clean = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = clean.createGraphics();
                    try {
                        graphics.setColor(Color.WHITE);
                        graphics.fillRect(0, 0, targetWidth, targetHeight);
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        graphics.drawImage(decoded, 0, 0, targetWidth, targetHeight, null);
                    } finally { graphics.dispose(); }
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    if (!ImageIO.write(clean, "jpeg", output)) throw new IllegalStateException("JPEG encoder unavailable");
                    return output.toByteArray();
                } finally { reader.dispose(); }
            }
        } catch (ResponseStatusException error) { throw error; }
        catch (Exception error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片无法解码", error); }
    }

    private Map<String, Object> upload(HttpServletRequest request, MultipartFile file, String role) {
        Api.Actor actor = api.actor(request, role);
        byte[] content = normalized(file);
        String key = UUID.randomUUID().toString();
        Path path = null;
        try {
            path = storage().resolve(key + ".jpg");
            Files.write(path, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            db.update("UPDATE " + table(role) + " SET avatar_key=? WHERE id=?", key, actor.profileId());
            return Map.of("code", 0, "avatarUrl", "/api/" + role.toLowerCase(java.util.Locale.ROOT) + "/avatar");
        } catch (Exception error) {
            if (path != null) try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "保存头像失败", error);
        }
    }

    private ResponseEntity<byte[]> avatar(HttpServletRequest request, String role) {
        Api.Actor actor = api.actor(request, role);
        String key = db.queryForObject("SELECT avatar_key FROM " + table(role) + " WHERE id=?", String.class, actor.profileId());
        if (key == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "尚未上传头像");
        try {
            return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
                    .cacheControl(CacheControl.noStore()).body(Files.readAllBytes(storage().resolve(key + ".jpg")));
        } catch (Exception error) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "头像不可用", error); }
    }

    @PostMapping(value = "/patient/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> patientUpload(HttpServletRequest request, @RequestPart("file") MultipartFile file) { return upload(request, file, "PATIENT"); }

    @GetMapping("/patient/avatar")
    public ResponseEntity<byte[]> patientAvatar(HttpServletRequest request) { return avatar(request, "PATIENT"); }

    @PostMapping(value = "/doctor/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> doctorUpload(HttpServletRequest request, @RequestPart("file") MultipartFile file) { return upload(request, file, "DOCTOR"); }

    @GetMapping("/doctor/avatar")
    public ResponseEntity<byte[]> doctorAvatar(HttpServletRequest request) { return avatar(request, "DOCTOR"); }
}
