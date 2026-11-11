package com.ailabs.devops.access;

/**
 * Resolves the credentials used to reach the secret stores themselves (the access server token,
 * the Vault token). These cannot come from the access server, so only {@code env:} and
 * {@code path:} are allowed.
 */
final class BootstrapSecrets {

    private BootstrapSecrets() {}

    static String resolve(String ref, String what) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        if (ref.startsWith("env:")) {
            return new EnvSecretProvider().fetch(ref.substring(4), what);
        }
        if (ref.startsWith("path:")) {
            return new PathSecretProvider().fetch(ref.substring(5), what);
        }
        throw new IllegalStateException(what + " must be an env: or path: reference, got '" + ref + "'");
    }
}
