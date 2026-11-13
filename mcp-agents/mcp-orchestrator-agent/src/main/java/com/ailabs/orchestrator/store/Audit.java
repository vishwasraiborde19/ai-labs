package com.ailabs.orchestrator.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One line per delegation, tool call, approval and registry change in {@code logs/audit.log}. */
public final class Audit {

    private static final Logger AUDIT = LoggerFactory.getLogger("audit");

    private Audit() {}

    public static void log(String actor, String action, String detail) {
        String d = detail == null ? "" : detail.replaceAll("\\s+", " ");
        if (d.length() > 500) d = d.substring(0, 500) + "...";
        AUDIT.info("[{}] {} {}", actor, action, d);
    }
}
