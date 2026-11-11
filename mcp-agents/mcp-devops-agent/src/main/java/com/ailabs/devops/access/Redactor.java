package com.ailabs.devops.access;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Remembers every secret value the agent has resolved and masks it in anything returned to the
 * model (tool results, error messages, build logs). A second line of defence: tools are written
 * not to return secrets in the first place.
 */
@Component
public class Redactor {

    static final String MASK = "****";
    private static final int MIN_LENGTH = 4;

    private final Set<String> secrets = ConcurrentHashMap.newKeySet();

    public void register(String secret) {
        if (secret != null && secret.length() >= MIN_LENGTH) {
            secrets.add(secret);
            // PEM/kubeconfig values span lines; also mask each long line on its own
            if (secret.contains("\n")) {
                for (String line : secret.split("\\R")) {
                    if (line.length() >= 16) {
                        secrets.add(line.trim());
                    }
                }
            }
        }
    }

    public String redact(String text) {
        if (text == null || text.isEmpty() || secrets.isEmpty()) {
            return text;
        }
        String out = text;
        for (String s : secrets) {
            if (out.contains(s)) {
                out = out.replace(s, MASK);
            }
        }
        return out;
    }
}
