package com.ailabs.kube.access;

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

import com.ailabs.kube.config.KubeProperties;

/**
 * {@code file:lab/token} looks the key up in a local YAML file ({@code kube.access.secrets-file},
 * default {@code ~/.kube-agent/secrets.yml}). Keys may be flat or nested on {@code /}.
 */
@Component
public class FileSecretProvider implements SecretProvider {

    private static final Logger log = LoggerFactory.getLogger(FileSecretProvider.class);

    private final Path path;
    private Map<String, Object> data;
    private long loadedMtime = -1;

    public FileSecretProvider(KubeProperties props) {
        String p = props.access().secretsFile();
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
        return v.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private void reloadIfChanged() {
        try {
            long mtime = Files.getLastModifiedTime(path).toMillis();
            if (data != null && mtime == loadedMtime) {
                return;
            }
            try {
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
                if (perms.contains(PosixFilePermission.GROUP_READ) || perms.contains(PosixFilePermission.OTHERS_READ)) {
                    log.warn("Secrets file {} is readable by group/others; run: chmod 600 {}", path, path);
                }
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX file system
            }
            try (Reader r = Files.newBufferedReader(path)) {
                Object loaded = new Yaml().load(r);
                data = loaded instanceof Map ? (Map<String, Object>) loaded : Map.of();
            }
            loadedMtime = mtime;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read secrets file " + path + ": " + e.getMessage(), e);
        }
    }
}
