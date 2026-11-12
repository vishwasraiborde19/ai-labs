package com.ailabs.kube.tools;

import java.util.ArrayList;
import java.util.List;

import com.ailabs.kube.http.HttpSupport;

/** Shared parameter descriptions and small helpers for the tool classes. */
final class Params {

    static final String CLUSTER = "Cluster name from the agent config; omit for the default (first) cluster";
    static final String KIND = "Resource type as kubectl accepts it: kind, plural or short name, optionally with "
            + "group (pod, deploy, svc, ingress, nodes, pvc, certificates.cert-manager.io, ...)";
    static final String NAMESPACE = "Namespace";
    static final String API_VERSION = "Optional apiVersion (e.g. apps/v1) when the type exists in several versions";
    static final String DRY_RUN = "Validate on the server without persisting anything (server-side dry run)";

    private Params() {}

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static int clamp(Integer v, int dflt, int min, int max) {
        return v == null ? dflt : Math.max(min, Math.min(max, v));
    }

    /** {@code ?a=b&c=d} from name/value pairs, skipping blank values. */
    static String query(String... kv) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (!blank(kv[i + 1])) {
                parts.add(kv[i] + "=" + HttpSupport.enc(kv[i + 1]));
            }
        }
        return parts.isEmpty() ? "" : "?" + String.join("&", parts);
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
