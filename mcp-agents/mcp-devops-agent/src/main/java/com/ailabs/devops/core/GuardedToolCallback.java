package com.ailabs.devops.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import com.ailabs.devops.access.Redactor;
import com.ailabs.devops.http.HttpSupport;

/**
 * Wraps a tool so that every call is written to the audit log and anything going back to the model
 * (result or error) passes through the {@link Redactor}.
 */
public class GuardedToolCallback implements ToolCallback {

    private static final Logger audit = LoggerFactory.getLogger("audit");

    private final ToolCallback delegate;
    private final Redactor redactor;

    public GuardedToolCallback(ToolCallback delegate, Redactor redactor) {
        this.delegate = delegate;
        this.redactor = redactor;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String tool = getToolDefinition().name();
        long t0 = System.currentTimeMillis();
        audit.info("CALL {} args={}", tool, HttpSupport.truncate(redactor.redact(toolInput), 1000));
        try {
            String result = toolContext == null ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
            audit.info("OK   {} {}ms", tool, System.currentTimeMillis() - t0);
            return redactor.redact(unquote(result));
        } catch (RuntimeException e) {
            String msg = redactor.redact(firstMessage(e));
            audit.warn("FAIL {} {}ms {}", tool, System.currentTimeMillis() - t0, msg);
            throw new IllegalStateException(msg);
        }
    }

    /**
     * Tools returning String (Jenkinsfiles, logs, config.xml) get JSON-encoded by Spring AI into one
     * escaped line; hand the model the plain text instead.
     */
    static String unquote(String result) {
        if (result != null && result.length() >= 2 && result.startsWith("\"") && result.endsWith("\"")) {
            try {
                return HttpSupport.JSON.readValue(result, String.class);
            } catch (java.io.IOException e) {
                return result;
            }
        }
        return result;
    }

    /** The first meaningful message in the cause chain (our own exceptions carry the useful text). */
    public static String firstMessage(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t.getMessage() != null && !t.getMessage().isBlank()) {
                return t.getMessage();
            }
        }
        return e.getClass().getSimpleName();
    }
}
