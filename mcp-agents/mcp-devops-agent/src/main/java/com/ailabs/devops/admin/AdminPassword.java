package com.ailabs.devops.admin;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import com.ailabs.devops.config.DevopsProperties;
import com.ailabs.devops.http.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The admin UI password, stored as a salted PBKDF2-HMAC-SHA256 hash in {@code <home>/admin.json}
 * (mode 600). Set or reset it with {@code java -jar mcp-devops-agent.jar admin-password}.
 */
public final class AdminPassword {

    static final int ITERATIONS = 310_000;
    static final int MIN_LENGTH = 12;  // default; see devops.admin.min-password-length

    private final Path file;

    public AdminPassword(Path file) {
        this.file = file;
    }

    public static AdminPassword atHome() {
        return new AdminPassword(DevopsProperties.home().resolve("admin.json"));
    }

    public Path file() {
        return file;
    }

    public boolean isSet() {
        return Files.exists(file);
    }

    public void set(char[] password) {
        set(password, MIN_LENGTH);
    }

    public void set(char[] password, int minLength) {
        if (password.length < minLength) {
            throw new IllegalArgumentException("Admin password must be at least " + minLength + " characters");
        }
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String json = HttpSupport.JSON.createObjectNode()
                .put("algorithm", "PBKDF2WithHmacSHA256")
                .put("iterations", ITERATIONS)
                .put("salt", Base64.getEncoder().encodeToString(salt))
                .put("hash", Base64.getEncoder().encodeToString(hash(password, salt, ITERATIONS)))
                .toString();
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), ".admin", ".tmp");
            Files.writeString(tmp, json);
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX file system
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    public boolean verify(char[] password) {
        if (!isSet() || password == null) {
            return false;
        }
        try {
            JsonNode n = HttpSupport.JSON.readTree(Files.readString(file));
            byte[] salt = Base64.getDecoder().decode(n.path("salt").asText());
            byte[] expected = Base64.getDecoder().decode(n.path("hash").asText());
            return MessageDigest.isEqual(expected, hash(password, salt, n.path("iterations").asInt(ITERATIONS)));
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    /** A random password for first start: 20 characters, no look-alikes. */
    public static char[] generate() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        char[] out = new char[20];
        for (int i = 0; i < out.length; i++) {
            out[i] = alphabet.charAt(r.nextInt(alphabet.length()));
        }
        return out;
    }

    static byte[] hash(char[] password, byte[] salt, int iterations) {
        try {
            KeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * {@code admin-password} command: reads DEVOPS_ADMIN_PASSWORD or prompts twice. The minimum length
     * comes from DEVOPS_ADMIN_MIN_PASSWORD_LENGTH (the command runs without loading config.yml).
     */
    public static void cli() {
        AdminPassword p = atHome();
        int min = MIN_LENGTH;
        String minEnv = System.getenv("DEVOPS_ADMIN_MIN_PASSWORD_LENGTH");
        if (minEnv != null && !minEnv.isBlank()) {
            min = Math.max(1, Integer.parseInt(minEnv.trim()));
        }
        char[] pw;
        String env = System.getenv("DEVOPS_ADMIN_PASSWORD");
        if (env != null && !env.isBlank()) {
            pw = env.toCharArray();
        } else {
            Console c = System.console();
            if (c == null) {
                System.err.println("No console; set DEVOPS_ADMIN_PASSWORD instead");
                System.exit(2);
                return;
            }
            pw = c.readPassword("New admin password (min %d chars): ", min);
            char[] again = c.readPassword("Repeat: ");
            if (pw == null || !Arrays.equals(pw, again)) {
                System.err.println("Passwords do not match");
                System.exit(2);
                return;
            }
        }
        try {
            p.set(pw, min);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
        }
        System.out.println("Admin password saved to " + p.file());
    }
}
