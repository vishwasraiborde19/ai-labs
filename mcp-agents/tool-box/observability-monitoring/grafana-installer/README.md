# Grafana-installer

One command that turns a fresh Ubuntu 22.04 VM into the lab's metrics stack: dashboards and alerting
for Kubernetes clusters, VMs and apps.

```
apps / k8s / VMs ──OTLP──▶ OpenTelemetry Collector ──metrics──▶ Prometheus ◀── Grafana
      (4317 gRPC, 4318 HTTP)      │  + this VM's host metrics    (OTLP receiver,     (default
                                  └──logs + traces──▶ Elasticsearch   localhost)    data source)
                                     (optional, see below)
```

| Component | Version | Source | Script |
|---|---|---|---|
| Grafana OSS | newest 13.x at install time, or `GF_VER` | `apt.grafana.com` (signed apt repo) | `install-grafana.sh` |
| Prometheus | 3.16.0 (`PROM_VER`) | GitHub release `.tar.gz`, sha256-verified | `install-prometheus.sh` |
| OpenTelemetry Collector (contrib) | 0.165.0 (`OTEL_VER`) | GitHub release `.deb`, sha256-verified | `install-otel-collector.sh` |

## Install

```bash
git clone https://github.com/vishwasraiborde19/ai-labs
./ai-labs/mcp-agents/tool-box/observability-monitoring/grafana-installer/install-grafana.sh
```

Run it as a user with sudo. It takes 2-3 minutes and installs Prometheus, then the collector, then
Grafana. It finishes with the Grafana URL and a generated `admin` password, which is shown only once.
`install-prometheus.sh` and `install-otel-collector.sh` also run on their own, e.g. a collector on
another VM that sends to this Prometheus.

| Variable | Default | |
|---|---|---|
| `WITH_PROMETHEUS` / `WITH_OTEL` | `1` / `1` | `0` skips that part on this VM |
| `GF_VER` | empty (newest) | pin a Grafana version, e.g. `13.3.0` |
| `GF_HOSTNAME` | the VM's IP | name or IP users browse to: goes into `root_url` and the certificate |
| `GF_PORT` | `3000` | Semaphore also uses 3000, so pick e.g. `3001` if both run on one VM |
| `GF_HTTPS` | `1` | `0` serves plain HTTP (lab only) |
| `GF_ADMIN` | `admin` | admin login |
| `GF_PROM_URL` | the local Prometheus | data source URL; set it to use a Prometheus elsewhere |
| `GF_ALLOW` | the VM's subnet | networks allowed through ufw, if ufw is active (`PROM_ALLOW`, `OTEL_ALLOW` likewise) |
| `PROM_VER` | `3.16.0` | bump to upgrade Prometheus |
| `PROM_LISTEN` / `PROM_PORT` | `127.0.0.1` / `9090` | Prometheus has no login, so it is local-only and read through Grafana. `0.0.0.0` exposes it |
| `PROM_RETENTION` / `PROM_RETENTION_SIZE` | `15d` / empty | how long (and optionally how much) to keep |
| `OTEL_VER` | `0.165.0` | bump to upgrade the collector |
| `OTEL_LISTEN` | `0.0.0.0` | address of the OTLP receivers (4317 gRPC, 4318 HTTP) |
| `OTEL_PROM_URL` | the local Prometheus | where the collector sends metrics |
| `OTEL_HOSTMETRICS` | `1` | collect this VM's CPU, memory, disk, filesystem and network metrics |
| `OTEL_ES_URL` / `OTEL_ES_API_KEY` / `OTEL_ES_CA` | empty | send logs and traces to Elasticsearch (below) |

```bash
GF_HOSTNAME=grafana.lab.local GF_PORT=3001 PROM_RETENTION=30d ./install-grafana.sh
```

All three scripts are idempotent. Re-running keeps Grafana's database, Prometheus's data, the
certificate and the admin password, and rewrites the configs from the variables.
* **Grafana**: re-running **upgrades** it to the newest release in the repo unless `GF_VER` is set.
  Between runs the package is on `apt-mark hold`.
* **Prometheus**: bump `PROM_VER`. Each release goes in `/opt/prometheus-<version>`, and
  `/opt/prometheus` points to the current one.
* **Collector**: bump `OTEL_VER`. Its config is kept over the package's default when it upgrades.

## What it sets up

**Grafana**: the `grafana` package under the systemd unit `grafana-server`, HTTPS on `:3000`.
* Settings are `GF_<SECTION>_<KEY>` variables in `/etc/grafana/grafana.env` (mode 640). They override
  `/etc/grafana/grafana.ini`, which stays as the package shipped it. The systemd drop-in
  `grafana-server.service.d/lab.conf` loads them.
* Sign-up is disabled and usage reporting is off.
* A self-signed certificate in `/etc/grafana/tls/` (825 days, SAN = hostname, VM IP, localhost).
  Replace `cert.pem` / `key.pem` with CA-signed files and restart to use your own.
* The admin password is set through `GF_SECURITY_ADMIN_PASSWORD` only when the database is created,
  then removed from `grafana.env`.
* `/etc/grafana/provisioning/datasources/lab-prometheus.yaml` adds the default data source
  *Prometheus* (uid `lab-prometheus`).

**Prometheus**: the `prometheus` systemd unit, user `prometheus`, data in `/var/lib/prometheus`.
* Listens on `127.0.0.1:9090` with the **OTLP receiver** on (`/api/v1/otlp/v1/metrics`). The
  resource attributes `host.name` and `k8s.*` become labels. Out-of-order samples are accepted within
  30 minutes.
* `/etc/prometheus/prometheus.yml` is managed by the script and scrapes Prometheus itself.
* Your own scrape jobs go in `/etc/prometheus/scrape.d/*.yml`. Each file has a top-level
  `scrape_configs:` list, and `sudo systemctl reload prometheus` picks it up. The installers drop in
  `grafana.yml` and `otel-collector.yml` there.

**OpenTelemetry Collector**: the `otelcol-contrib` package and unit, config in
`/etc/otelcol-contrib/config.yaml` (mode 640). It is validated with `otelcol-contrib validate` before
each restart.
* OTLP receivers on `0.0.0.0:4317` (gRPC) and `:4318` (HTTP), with no authentication: keep them on
  the lab network (ufw allows only `OTEL_ALLOW`).
* Pipelines:
  * `metrics`: OTLP to Prometheus.
  * `metrics/host`: this VM's host metrics to Prometheus, as job `hostmetrics`.
  * `logs` / `traces`: only with Elasticsearch configured.
* Health check on `127.0.0.1:13133`. Its own metrics are on `127.0.0.1:8888` and scraped by Prometheus.
* The installer sends one test metric, `lab_install_check`, through the whole path.

## First steps

1. Open Grafana → *Explore* → *Prometheus* and run `lab_install_check` or `system_cpu_time_seconds_total`.
2. **Send telemetry**: point apps' OpenTelemetry SDKs at the collector with
   `OTEL_EXPORTER_OTLP_ENDPOINT=http://<vm>:4318`. For Kubernetes, run a collector DaemonSet
   (`k8s` distribution / opentelemetry-operator) that exports with `otlphttp` to `http://<vm>:4318`.
   Add `k8s.cluster.name` as a resource attribute so clusters can be told apart.
3. **More scrape targets**: e.g. node_exporter on other VMs.

   ```yaml
   # /etc/prometheus/scrape.d/vms.yml
   scrape_configs:
     - job_name: node
       static_configs:
         - targets: ["10.0.0.11:9100", "10.0.0.12:9100"]
   ```
4. **Dashboards**: import community dashboards by ID (*Dashboards → New → Import*), or build on the
   `system_*` host metrics.

### Logs and traces to Elasticsearch

With [elastic-kibana-installer](../../elastic-kibana/elastic-kibana-installer/) running, create an API
key that can only write telemetry:

```bash
curl --cacert /etc/elasticsearch/certs/http_ca.crt -u elastic -X POST \
  https://localhost:9200/_security/api_key -H 'Content-Type: application/json' -d '{
  "name": "otel-collector",
  "role_descriptors": {"otel": {"indices": [{"names": ["logs-*", "traces-*", "metrics-*"],
                                             "privileges": ["auto_configure", "create_doc"]}]}}}'
```

Then re-run with its `encoded` value. If Elasticsearch is on another VM, copy its
`/etc/elasticsearch/certs/http_ca.crt` here and pass it as `OTEL_ES_CA`.

```bash
OTEL_ES_URL=https://<es-ip>:9200 OTEL_ES_API_KEY=<encoded> ./install-otel-collector.sh
```

Logs and traces then show up in Kibana (*Discover*, *Observability → Applications*). To query them from
Grafana, add an Elasticsearch data source (*Connections → Data sources*): URL `https://<es-ip>:9200`,
*With CA Cert* = `http_ca.crt`, basic auth with a read-only user (see that README).

### Single sign-on with Keycloak

In Keycloak, create a confidential OIDC client `grafana` in the lab realm with redirect URI
`https://<grafana>:3000/login/generic_oauth`. Then append the following to `/etc/grafana/grafana.env`
and run `sudo systemctl restart grafana-server`. Re-running the script rewrites that file, so keep a
copy of these lines.

```bash
GF_AUTH_GENERIC_OAUTH_ENABLED=true
GF_AUTH_GENERIC_OAUTH_NAME=Keycloak
GF_AUTH_GENERIC_OAUTH_CLIENT_ID=grafana
GF_AUTH_GENERIC_OAUTH_CLIENT_SECRET=<client secret>
GF_AUTH_GENERIC_OAUTH_SCOPES=openid email profile
GF_AUTH_GENERIC_OAUTH_AUTH_URL=https://<keycloak>:8443/realms/lab/protocol/openid-connect/auth
GF_AUTH_GENERIC_OAUTH_TOKEN_URL=https://<keycloak>:8443/realms/lab/protocol/openid-connect/token
GF_AUTH_GENERIC_OAUTH_API_URL=https://<keycloak>:8443/realms/lab/protocol/openid-connect/userinfo
GF_AUTH_GENERIC_OAUTH_USE_PKCE=true
GF_AUTH_GENERIC_OAUTH_TLS_CLIENT_CA=/etc/grafana/keycloak-ca.pem   # copy of Keycloak's cert.pem
GF_AUTH_GENERIC_OAUTH_ROLE_ATTRIBUTE_PATH=contains(groups[*], 'grafana-admins') && 'Admin' || 'Viewer'
```

## Operations

```bash
journalctl -u grafana-server -f; journalctl -u prometheus -f; journalctl -u otelcol-contrib -f   # logs
curl -k https://127.0.0.1:3000/api/health; curl http://127.0.0.1:9090/-/ready; curl http://127.0.0.1:13133/
curl -s http://127.0.0.1:9090/api/v1/targets | python3 -m json.tool | grep -E '"(job|health)"'  # scrape status
sudo systemctl reload prometheus                           # after editing scrape.d/
sudo systemctl stop grafana-server && sudo cp /var/lib/grafana/grafana.db grafana.db.bak \
  && sudo systemctl start grafana-server                   # Grafana backup
# lost Grafana admin password
sudo grafana cli --homepath /usr/share/grafana --config /etc/grafana/grafana.ini \
  admin reset-admin-password 'new-password'
```
