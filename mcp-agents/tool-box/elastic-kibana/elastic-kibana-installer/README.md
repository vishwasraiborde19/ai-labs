# Elastic-kibana-installer

A single script that turns a fresh Ubuntu 22.04 VM into a single-node
[Elasticsearch](https://www.elastic.co/elasticsearch) + [Kibana](https://www.elastic.co/kibana)
server, a place to store and search the lab's logs and metrics (Kubernetes logs through Filebeat or
Elastic Agent, Ansible/Semaphore runs, VM syslogs) with security on from the start.

| Component | Version | Source |
|---|---|---|
| Elasticsearch | newest 9.x at install time, or `ES_VER` | `artifacts.elastic.co` (signed apt repo), bundled JDK |
| Kibana | same version as Elasticsearch | same repo |

## Install

```bash
git clone https://github.com/vishwasraiborde19/ai-labs
./ai-labs/mcp-agents/tool-box/elastic-kibana/elastic-kibana-installer/install-elastic-kibana.sh
```

Run it as a user with sudo on a VM with **4 GB RAM or more** (8 GB is comfortable). It takes 3-5
minutes and finishes with the Kibana URL and a generated `elastic` password, which is shown only once.

| Variable | Default | |
|---|---|---|
| `ES_VER` | empty (newest) | pin a version for both, e.g. `9.5.5` |
| `ES_MAJOR` | `9` (or from `ES_VER`) | which apt repo line (`9.x`) to use |
| `ES_HEAP` | empty (automatic) | Elasticsearch JVM heap, e.g. `2g` |
| `KB_HOSTNAME` | the VM's IP | name or IP users browse to: goes into `publicBaseUrl` and the certificate |
| `KB_PORT` | `5601` | |
| `ES_EXPOSE` | `1` | `0` opens no ufw rule for `9200`, only for Kibana |
| `ES_ALLOW` | the VM's subnet | networks allowed through ufw, if ufw is active |

```bash
KB_HOSTNAME=kibana.lab.local ES_HEAP=2g ./install-elastic-kibana.sh
```

The script is idempotent. Re-running it keeps the data, the certificates, the `elastic` password, the
Kibana token and the encryption keys, and rewrites `kibana.yml` from the variables. Re-running it also
**upgrades** both packages to the newest release of `ES_MAJOR` unless `ES_VER` is set. Between runs
the packages are on `apt-mark hold`, so `apt upgrade` can't upgrade one without the other. Take a
snapshot before an upgrade. For a major upgrade (e.g. to 10.x), first read Elastic's upgrade notes,
then set `ES_MAJOR` or `ES_VER`.

## What it sets up

* **Elasticsearch** under the systemd unit `elasticsearch`. On first install the package
  auto-configures security: TLS on HTTP (`:9200`) and transport, with its own CA in
  `/etc/elasticsearch/certs/http_ca.crt`, and authentication on. The script leaves the package's
  `elasticsearch.yml` as it is and only adds `jvm.options.d/heap.options` when `ES_HEAP` is set.
  Data is in `/var/lib/elasticsearch`, logs in `/var/log/elasticsearch`.
* **`elastic` password**: the password printed by the package is discarded and reset with
  `elasticsearch-reset-password` once the node is up. This happens only after a first install.
* **Kibana** under the systemd unit `kibana`, HTTPS on `:5601` with a self-signed certificate in
  `/etc/kibana/tls/` (825 days, SAN = hostname, VM IP, localhost). It connects to Elasticsearch with a
  file-based service-account token `elastic/kibana/kibana-lab`, not with a password, and trusts the
  Elasticsearch CA copied to `/etc/kibana/certs/`.
* `/etc/kibana/kibana.yml` (mode 640, root:kibana) is written by the script. The token is in
  `/etc/kibana/.es-token`. The three saved-object / reporting / session encryption keys are in
  `/etc/kibana/.keys` (all mode 600). Don't lose or change the keys: Kibana couldn't decrypt alerts,
  connectors and other saved objects any more.

## First steps

1. Log in to Kibana as `elastic`, then create named users under *Stack Management → Users* and stop
   using `elastic` day to day.
2. **Ship data**: install Elastic Agent or Filebeat on the VMs / as a Kubernetes DaemonSet. Point it at
   `https://<es-ip>:9200` with the CA `http_ca.crt` and an API key (*Stack Management → API keys*).
3. **Grafana** (from [grafana-installer](../../observability-monitoring/grafana-installer/)): create a read-only user
   for it.

   ```bash
   curl --cacert /etc/elasticsearch/certs/http_ca.crt -u elastic -X POST \
     https://localhost:9200/_security/role/grafana_reader -H 'Content-Type: application/json' \
     -d '{"cluster":["monitor"],"indices":[{"names":["*"],"privileges":["read","view_index_metadata"]}]}'
   curl --cacert /etc/elasticsearch/certs/http_ca.crt -u elastic -X POST \
     https://localhost:9200/_security/user/grafana -H 'Content-Type: application/json' \
     -d '{"password":"<choose one>","roles":["grafana_reader"]}'
   ```
4. **OpenTelemetry**: the collector from grafana-installer can send logs and traces here. See
   [Logs and traces to Elasticsearch](../../observability-monitoring/grafana-installer/README.md#logs-and-traces-to-elasticsearch).
5. **Single sign-on with Keycloak**: Kibana's OIDC realm needs an Elastic subscription (Platinum /
   trial), so on the free Basic tier use Elasticsearch native users.

## Operations

```bash
journalctl -u kibana -f;  sudo tail -f /var/log/elasticsearch/elasticsearch.log    # logs
sudo curl --cacert /etc/elasticsearch/certs/http_ca.crt -u elastic \
  'https://localhost:9200/_cluster/health?pretty'                                   # health
sudo /usr/share/elasticsearch/bin/elasticsearch-reset-password -u elastic -i        # new elastic password
# backups: register a snapshot repository (path.repo in elasticsearch.yml), then
#   PUT _snapshot/<repo>/<name>  - or Stack Management -> Snapshot and Restore
```

A single node can't place replica shards, so the cluster health is **yellow** once indices with
replicas exist. Set `"number_of_replicas": 0` in index templates to make it green, or add nodes later.
