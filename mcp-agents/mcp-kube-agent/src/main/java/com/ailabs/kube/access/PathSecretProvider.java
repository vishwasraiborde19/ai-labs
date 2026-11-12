package com.ailabs.kube.access;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

/**
 * {@code path:/abs/file} returns the trimmed contents of a file. Handy for CA certificates and
 * mounted token files such as {@code /var/run/secrets/kubernetes.io/serviceaccount/token}.
 */
@Component
public class PathSecretProvider implements SecretProvider {

    @Override
    public String scheme() {
        return "path";
    }

    @Override
    public String fetch(String key, String purpose) {
        Path p = Path.of(key.startsWith("~/") ? System.getProperty("user.home") + key.substring(1) : key);
        try {
            return Files.readString(p).trim();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + p + ": " + e.getMessage(), e);
        }
    }
}
