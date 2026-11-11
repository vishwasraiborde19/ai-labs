package com.ailabs.vmadmin;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The control centre's audit trail ({@code logs/audit.log}): who did what on which VM. The actor is
 * set per request by the UI and MCP filters ({@code ui@127.0.0.1}, {@code mcp}).
 */
public final class Audit {

    private static final Logger log = LoggerFactory.getLogger("audit");
    private static final ThreadLocal<String> ACTOR = ThreadLocal.withInitial(() -> "system");

    private Audit() {}

    public static void actor(String actor) {
        ACTOR.set(actor);
    }

    public static void clear() {
        ACTOR.remove();
    }

    public static String actor() {
        return ACTOR.get();
    }

    /** One line: {@code [actor] host=web-01 RUN cwd=/ cmd=uptime}. Detail is cut at 2000 chars. */
    public static void log(String host, String action, String detail) {
        String d = detail == null ? "" : detail.replace('\n', ' ');
        if (d.length() > 2000) {
            d = d.substring(0, 2000) + "...";
        }
        log.info("[{}] host={} {} {}", ACTOR.get(), host == null ? "-" : host, action, d);
    }

    public static Path file() {
        String dir = System.getenv("VMADMIN_LOG_DIR");
        return (dir != null && !dir.isBlank() ? Path.of(dir) : com.ailabs.vmadmin.config.AdminProperties.home().resolve("logs"))
                .resolve("audit.log");
    }

    /** The last {@code n} lines, newest last. */
    public static List<String> tail(int n) {
        Path f = file();
        if (!Files.exists(f)) {
            return List.of();
        }
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
            long len = raf.length();
            long from = Math.max(0, len - 256_000);
            raf.seek(from);
            byte[] buf = new byte[(int) (len - from)];
            raf.readFully(buf);
            List<String> lines = new ArrayList<>(List.of(new String(buf, StandardCharsets.UTF_8).split("\n")));
            if (from > 0 && !lines.isEmpty()) {
                lines.remove(0);  // partial first line
            }
            return lines.subList(Math.max(0, lines.size() - n), lines.size());
        } catch (IOException e) {
            return List.of("cannot read " + f + ": " + e.getMessage());
        }
    }
}
