package com.ailabs.devops.access;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import com.ailabs.devops.config.DevopsProperties;

/**
 * {@code file:jenkins/main/api-token} looks the key up in a local YAML secrets file
 * ({@code devops.access.file.path}, default {@code ~/.devops-agent/secrets.yml}).
 * Keys may be flat ({@code "jenkins/main/api-token": xxx}) or nested on {@code /}.
 * Intended for laptops and labs until the access server is running.
 */
@Component
public class FileSecretProvider implements SecretProvider {

    private static final Logger log = LoggerFactory.getLogger(FileSecretProvider.class);

    private final Path path;
    private Map<String, Object> data;
    private long loadedMtime = -1;

    public FileSecretProvider(DevopsProperties props) {
        String p = props.access().file() != null ? props.access().file().path() : null;
        if (p == null || p.isBlank()) {
            p = "~/.devops-agent/secrets.yml";
        }
        this.path = Path.of(p.startsWith("~/") ? System.getProperty("user.home") + p.substring(1) : p);
    }

    @Override
    public String scheme() {
        return "file";
    }

    @Override
    public synchronized String fetch(String key, String purpose) {
        reloadIfChanged();
        Object v = data.get(key);
        if (v == null) {
            Object node = data;
            for (String part : key.split("/")) {
                node = node instanceof Map<?, ?> m ? m.get(part) : null;
            }
            v = node;
        }
        if (v == null || v instanceof Map) {
            throw new IllegalStateException("Secret '" + key + "' not found in " + path);
        }
        return v.toString();
    }

    @SuppressWarnings("unchecked")
    private void reloadIfChanged() {
        try {
            long mtime = Files.getLastModifiedTime(path).toMillis();
            if (data != null && mtime == loadedMtime) {
                return;
            }
            warnIfReadableByOthers();
            try (Reader r = Files.newBufferedReader(path)) {
                Object loaded = new Yaml().load(r);
                data = loaded instanceof Map ? (Map<String, Object>) loaded : Map.of();
            }
            loadedMtime = mtime;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read secrets file " + path + ": " + e.getMessage(), e);
        }
    }

    private void warnIfReadableByOthers() {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
            if (perms.contains(PosixFilePermission.GROUP_READ) || perms.contains(PosixFilePermission.OTHERS_READ)) {
                log.warn("Secrets file {} is readable by group/others; run: chmod 600 {}", path, path);
            }
        } catch (UnsupportedOperationException | IOException ignored) {
            // non-POSIX file system
        }
    }
}
