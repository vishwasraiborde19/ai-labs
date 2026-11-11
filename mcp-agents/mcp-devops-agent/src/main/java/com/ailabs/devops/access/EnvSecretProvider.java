package com.ailabs.devops.access;

import org.springframework.stereotype.Component;

/** {@code env:NAME} reads an environment variable of the agent process. */
@Component
public class EnvSecretProvider implements SecretProvider {

    @Override
    public String scheme() {
        return "env";
    }

    @Override
    public String fetch(String key, String purpose) {
        String v = System.getenv(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException("Environment variable " + key + " is not set");
        }
        return v;
    }
}
