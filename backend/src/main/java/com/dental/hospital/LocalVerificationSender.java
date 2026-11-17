package com.dental.hospital;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;

@Component
public class LocalVerificationSender implements VerificationSender {
    private final Path outbox;

    public LocalVerificationSender(@Value("${VERIFICATION_OUTBOX_DIR:}") String directory) {
        outbox = directory.isBlank() ? null : Path.of(directory).toAbsolutePath().normalize();
    }

    @Override
    public void send(String purpose, String destination, String code) {
        if (outbox == null) throw new IllegalStateException("VERIFICATION_OUTBOX_DIR is required for local verification delivery");
        try {
            Path home = Path.of(System.getProperty("user.home")).toRealPath();
            Files.createDirectories(outbox);
            Path realOutbox = outbox.toRealPath();
            if (!realOutbox.startsWith(home) || realOutbox.equals(home))
                throw new IllegalStateException("Verification outbox must be a private directory under the application user's home");
            Path file = realOutbox.resolve("verification-" + UUID.randomUUID() + ".txt");
            Files.writeString(file, "purpose=" + purpose + "\ndestination=" + destination + "\ncode=" + code + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try { Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
            catch (UnsupportedOperationException ignored) { /* Windows inherits the private user-profile ACL. */ }
        } catch (IOException error) {
            throw new IllegalStateException("Unable to deliver local verification code", error);
        }
    }
}
