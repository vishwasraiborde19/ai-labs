package com.ailabs.devops.access;

/**
 * One source of secrets, selected by the scheme of a secret reference ({@code scheme:key}).
 * Implementations must never log the value they return.
 */
public interface SecretProvider {

    /** The scheme this provider answers to, e.g. {@code access}, {@code env}, {@code vault}. */
    String scheme();

    /**
     * @param key     the part of the reference after {@code scheme:}
     * @param purpose why the agent needs it (e.g. {@code jenkins:main}); forwarded to servers for their audit
     * @return the secret value, never null
     * @throws IllegalStateException if the secret does not exist or cannot be fetched
     */
    String fetch(String key, String purpose);
}
