package com.ailabs.vmadmin.metrics;

import java.util.Map;

/** Exposes the package-private sample parser to tests in other packages. */
public final class MetricsServiceTestAccess {

    private MetricsServiceTestAccess() {}

    public static MetricsService.Sample parse(Map<String, Object> sysinfo) {
        return MetricsService.parse(sysinfo, 0L);
    }
}
