package com.ailabs.vmadmin.pve;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.ailabs.vmadmin.fleet.FleetService;
import com.ailabs.vmadmin.fleet.Host;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The hypervisor view: cluster and quorum, nodes, and every VM and container with its usage, IP
 * addresses and the fleet host (worker agent) that runs on it, matched by IP or by name.
 *
 * <p>Read-only. Needs a token with {@code PVEAuditor} on {@code /}. Guest IPs come from the QEMU
 * guest agent when it runs and the token may query it ({@code VM.GuestAgent.Audit} on PVE 9,
 * {@code VM.Monitor} on PVE 8), otherwise from the cloud-init {@code ipconfigN} or LXC {@code netN}
 * settings, which only show static addresses.
 */
@Service
public class ProxmoxService {

    private static final long OVERVIEW_TTL_MS = 5_000;
    private static final Pattern IP_SETTING = Pattern.compile("(?:^|,)ip=([0-9.]+)(?:/\\d+)?(?:,|$)");
    private static final Set<String> SKIP_IFACE_PREFIX = Set.of("lo", "docker", "br-", "veth", "cni", "flannel",
            "cali", "kube-", "tunl", "vxlan", "virbr", "tailscale", "lxc");

    private final ProxmoxClient pve;
    private final FleetService fleet;
    private final ProxmoxProperties props;

    private record Ips(List<String> ips, String source, long at) {}

    private final Map<Integer, Ips> ipCache = new ConcurrentHashMap<>();
    private volatile Map<String, Object> cached;
    private volatile long cachedAt;

    public ProxmoxService(ProxmoxClient pve, FleetService fleet, ProxmoxProperties props) {
        this.pve = pve;
        this.fleet = fleet;
        this.props = props;
    }

    public boolean configured() {
        return pve.configured();
    }

    public void invalidate() {
        cached = null;
        ipCache.clear();
    }

    /** Cluster, nodes and guests. Reused for 5 s so several open browser tabs don't multiply API calls. */
    public synchronized Map<String, Object> overview() {
        if (!configured()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("configured", false);
            m.put("endpoints", props.endpoints());
            return m;
        }
        if (cached != null && System.currentTimeMillis() - cachedAt < OVERVIEW_TTL_MS) {
            return cached;
        }
        JsonNode status = pve.get("/cluster/status");
        JsonNode resources = pve.get("/cluster/resources");

        Map<String, Object> cluster = new LinkedHashMap<>();
        Map<String, JsonNode> nodeStatus = new HashMap<>();
        for (JsonNode s : status) {
            if ("cluster".equals(s.path("type").asText())) {
                cluster.put("name", s.path("name").asText());
                cluster.put("quorate", s.path("quorate").asInt() == 1);
                cluster.put("nodes", s.path("nodes").asInt());
                cluster.put("version", s.path("version").asInt());
            } else if ("node".equals(s.path("type").asText())) {
                nodeStatus.put(s.path("name").asText(), s);
            }
        }
        if (cluster.isEmpty()) {          // a single node that is not in a cluster
            cluster.put("name", null);
            cluster.put("quorate", true);
            cluster.put("nodes", nodeStatus.size());
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<JsonNode> guestNodes = new ArrayList<>();
        for (JsonNode r : resources) {
            String type = r.path("type").asText();
            if ("node".equals(type)) {
                JsonNode ns = nodeStatus.get(r.path("node").asText());
                Map<String, Object> n = usage(r);
                n.put("name", r.path("node").asText());
                n.put("status", r.path("status").asText("unknown"));
                n.put("ip", ns != null ? ns.path("ip").asText(null) : null);
                n.put("local", ns != null && ns.path("local").asInt() == 1);
                nodes.add(n);
            } else if ("qemu".equals(type) || "lxc".equals(type)) {
                guestNodes.add(r);
            }
        }
        nodes.sort(Comparator.comparing(n -> String.valueOf(n.get("name"))));

        Map<Integer, Ips> ips = guestIps(guestNodes);
        Map<String, String> fleetByAddr = new HashMap<>();
        Map<String, String> fleetState = new HashMap<>();
        for (Host h : fleet.store().snapshot().fleet().hosts()) {
            try {
                fleetByAddr.put(URI.create(h.url()).getHost(), h.name());
            } catch (IllegalArgumentException ignored) {
                // invalid URLs are reported by the fleet view
            }
        }
        fleet.statuses().forEach(s -> fleetState.put(s.host(), s.state()));
        Set<String> fleetNames = fleetState.keySet();

        List<Map<String, Object>> guests = new ArrayList<>();
        int running = 0;
        for (JsonNode r : guestNodes) {
            int vmid = r.path("vmid").asInt();
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("vmid", vmid);
            g.put("name", r.path("name").asText(null));
            g.put("type", r.path("type").asText());
            g.put("node", r.path("node").asText());
            g.put("status", r.path("status").asText("unknown"));
            g.put("template", r.path("template").asInt() == 1);
            g.putAll(usage(r));
            g.put("tags", splitTags(r.path("tags").asText("")));
            g.put("lock", r.path("lock").asText(null));
            g.put("hastate", r.path("hastate").asText(null));
            Ips ip = ips.get(vmid);
            g.put("ips", ip != null ? ip.ips() : List.of());
            g.put("ipSource", ip != null ? ip.source() : null);
            String fh = null;
            if (ip != null) {
                for (String a : ip.ips()) {
                    if (fleetByAddr.containsKey(a)) {
                        fh = fleetByAddr.get(a);
                        break;
                    }
                }
            }
            if (fh == null && g.get("name") != null && fleetNames.contains(g.get("name"))) {
                fh = (String) g.get("name");
            }
            g.put("fleetHost", fh);
            g.put("fleetState", fh != null ? fleetState.get(fh) : null);
            if ("running".equals(g.get("status"))) {
                running++;
            }
            guests.add(g);
        }
        guests.sort(Comparator.comparingInt(g -> (Integer) g.get("vmid")));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", true);
        m.put("cluster", cluster);
        m.put("nodes", nodes);
        m.put("guests", guests);
        m.put("summary", Map.of("guests", guests.size(), "running", running,
                "vms", guests.stream().filter(g -> "qemu".equals(g.get("type"))).count(),
                "containers", guests.stream().filter(g -> "lxc".equals(g.get("type"))).count(),
                "inFleet", guests.stream().filter(g -> g.get("fleetHost") != null).count()));
        m.put("endpoint", pve.lastEndpoint());
        m.put("endpoints", props.endpoints());
        m.put("fetchedAt", Instant.now().toString());
        cached = m;
        cachedAt = System.currentTimeMillis();
        return m;
    }

    private static Map<String, Object> usage(JsonNode r) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("cpu", r.path("cpu").asDouble());          // fraction of maxcpu, 0..1
        u.put("maxcpu", r.path("maxcpu").asInt());
        u.put("mem", r.path("mem").asLong());
        u.put("maxmem", r.path("maxmem").asLong());
        u.put("disk", r.path("disk").asLong());
        u.put("maxdisk", r.path("maxdisk").asLong());
        u.put("uptime", r.path("uptime").asLong());
        return u;
    }

    static List<String> splitTags(String t) {
        if (t == null || t.isBlank()) {
            return List.of();
        }
        return List.of(t.split("[;,\\s]+")).stream().filter(s -> !s.isBlank()).toList();
    }

    // ------------------------------------------------------------------ guest IPs

    /** One lookup per guest, in parallel, cached for ip-cache-ttl. Failures leave a guest without IPs. */
    private Map<Integer, Ips> guestIps(List<JsonNode> guests) {
        long now = System.currentTimeMillis();
        long ttl = props.ipCacheTtl().toMillis();
        Map<Integer, Ips> out = new HashMap<>();
        List<Callable<Void>> work = new ArrayList<>();
        Set<Integer> seen = new java.util.HashSet<>();
        for (JsonNode g : guests) {
            int vmid = g.path("vmid").asInt();
            seen.add(vmid);
            if (g.path("template").asInt() == 1) {
                continue;
            }
            Ips c = ipCache.get(vmid);
            // a guest that just started gets a fresh lookup once it has been up for a while
            boolean stale = c == null || now - c.at() > ttl
                    || ("running".equals(g.path("status").asText()) && c.ips().isEmpty() && now - c.at() > 20_000);
            if (!stale) {
                out.put(vmid, c);
                continue;
            }
            work.add(() -> {
                Ips v = lookup(g);
                ipCache.put(vmid, v);
                return null;
            });
        }
        if (!work.isEmpty()) {
            try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
                ex.invokeAll(work, props.timeout().toMillis() + 2_000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (Iterator<Integer> it = ipCache.keySet().iterator(); it.hasNext(); ) {
            if (!seen.contains(it.next())) {
                it.remove();     // guest deleted
            }
        }
        for (JsonNode g : guests) {
            Ips c = ipCache.get(g.path("vmid").asInt());
            if (c != null) {
                out.put(g.path("vmid").asInt(), c);
            }
        }
        return out;
    }

    private Ips lookup(JsonNode g) {
        String node = g.path("node").asText();
        int vmid = g.path("vmid").asInt();
        boolean running = "running".equals(g.path("status").asText());
        long now = System.currentTimeMillis();
        if ("qemu".equals(g.path("type").asText())) {
            if (running) {
                try {
                    List<String> a = agentIps(pve.get("/nodes/" + node + "/qemu/" + vmid + "/agent/network-get-interfaces"));
                    if (!a.isEmpty()) {
                        return new Ips(a, "guest-agent", now);
                    }
                } catch (RuntimeException e) {
                    // no guest agent in the VM, or the token may not query it: use the static config
                }
            }
            try {
                return new Ips(configIps(pve.get("/nodes/" + node + "/qemu/" + vmid + "/config"), "ipconfig"), "cloud-init", now);
            } catch (RuntimeException e) {
                return new Ips(List.of(), null, now);
            }
        }
        if (running) {
            try {
                List<String> a = lxcIps(pve.get("/nodes/" + node + "/lxc/" + vmid + "/interfaces"));
                if (!a.isEmpty()) {
                    return new Ips(a, "container", now);
                }
            } catch (RuntimeException e) {
                // fall through to the config
            }
        }
        try {
            return new Ips(configIps(pve.get("/nodes/" + node + "/lxc/" + vmid + "/config"), "net"), "config", now);
        } catch (RuntimeException e) {
            return new Ips(List.of(), null, now);
        }
    }

    /** {@code {"result":[{"name":"eth0","ip-addresses":[{"ip-address-type":"ipv4","ip-address":"..."}]}]}} */
    static List<String> agentIps(JsonNode data) {
        List<String> out = new ArrayList<>();
        for (JsonNode iface : data.path("result")) {
            if (skipIface(iface.path("name").asText())) {
                continue;
            }
            for (JsonNode a : iface.path("ip-addresses")) {
                String ip = a.path("ip-address").asText();
                if ("ipv4".equals(a.path("ip-address-type").asText()) && usable(ip) && !out.contains(ip)) {
                    out.add(ip);
                }
            }
        }
        return out;
    }

    /** {@code [{"name":"eth0","inet":"192.168.1.5/24"}, ...]} */
    static List<String> lxcIps(JsonNode data) {
        List<String> out = new ArrayList<>();
        for (JsonNode iface : data) {
            if (skipIface(iface.path("name").asText())) {
                continue;
            }
            String inet = iface.path("inet").asText("");
            String ip = inet.contains("/") ? inet.substring(0, inet.indexOf('/')) : inet;
            if (usable(ip) && !out.contains(ip)) {
                out.add(ip);
            }
        }
        return out;
    }

    /** Static addresses from {@code ipconfig0..N} (QEMU cloud-init) or {@code net0..N} (LXC): {@code ip=192.168.1.5/24,gw=...}. */
    static List<String> configIps(JsonNode config, String prefix) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            String v = config.path(prefix + i).asText("");
            Matcher m = IP_SETTING.matcher(v);
            if (m.find() && usable(m.group(1)) && !out.contains(m.group(1))) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    private static boolean skipIface(String name) {
        return SKIP_IFACE_PREFIX.stream().anyMatch(name::startsWith);
    }

    private static boolean usable(String ip) {
        return ip != null && ip.matches("\\d{1,3}(\\.\\d{1,3}){3}") && !ip.startsWith("127.") && !ip.startsWith("169.254.");
    }
}
