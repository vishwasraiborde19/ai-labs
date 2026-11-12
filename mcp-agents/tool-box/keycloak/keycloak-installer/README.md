# Keycloak-installer

A single script that turns a fresh Ubuntu 22.04 VM into a [Keycloak](https://www.keycloak.org) server:
an OpenID Connect / SAML identity provider for single sign-on to the lab's tools (Semaphore UI,
Kubernetes `kubectl`, the VM control centre) — a self-hosted alternative to Microsoft Entra ID, which
it can also federate with later.

| Component | Version | Source |
|---|---|---|
| Keycloak | 26.8.2 | GitHub release `.tar.gz`, sha1-verified |
| OpenJDK | 21 (headless JRE) | Ubuntu |
| PostgreSQL | 14 | Ubuntu, bound to localhost only |

## Install

```bash
git clone https://github.com/vishwasraiborde19/ai-labs
./ai-labs/mcp-agents/tool-box/keycloak/keycloak-installer/install-keycloak.sh
```

Run it as a user with sudo. It takes 2-3 minutes and finishes with the admin console URL and a
generated `admin` password.

| Variable | Default | |
|---|---|---|
| `KC_VER` | `26.8.2` | Keycloak release; bump to upgrade |
| `KC_HOSTNAME` | the VM's IP | name or IP clients use: it is in every token's issuer and in the certificate |
| `KC_HTTPS_PORT` | `8443` | |
| `KC_HTTP` / `KC_HTTP_PORT` | `0` / `8080` | `1` also serves plain HTTP (lab only) |
| `KC_ALLOW` | the VM's subnet | networks allowed through ufw, if ufw is active |
| `KC_ADMIN` | `admin` | bootstrap admin login |

```bash
KC_HOSTNAME=sso.lab.local KC_VER=26.8.2 ./install-keycloak.sh
```

Pick `KC_HOSTNAME` before connecting clients: changing it later changes the issuer URL, and every
client (Kubernetes API server, Semaphore, ...) has to be updated.

The script is idempotent. Re-running it keeps the database, the certificate and the admin password,
and rewrites `keycloak.conf` from the variables. Bumping `KC_VER` installs the new release next to the
old one (`/opt/keycloak-<version>`, `/opt/keycloak` points to the current one) and the database
migrates on first start. Back up the database first.

## What it sets up

* Keycloak runs as the `keycloak` system user under the systemd unit `keycloak`, in production mode
  (`kc.sh start --optimized`), HTTPS on `:8443`. Health and metrics are on the management port `:9000`
  (`/health/ready`, `/metrics`).
* Config in `/etc/keycloak/keycloak.conf` (mode 640, root:keycloak). DB password in
  `/etc/keycloak/.dbpass` (mode 600).
* A self-signed certificate in `/etc/keycloak/tls/` (825 days, SAN = hostname, VM IP, localhost).
  Replace `cert.pem` / `key.pem` with CA-signed files and restart to use your own.
* PostgreSQL database `keycloak`, owned by role `keycloak`.
* The first admin is created with `kc.sh bootstrap-admin` and is a *temporary* admin: log in, create a
  permanent admin user in the `master` realm, then delete the temporary one.

## First steps

1. **Realm**: create one realm for the lab (e.g. `lab`); keep `master` for administration only.
2. **Users and groups**: add users, and groups that map to roles (e.g. `k8s-admins`, `semaphore-admins`).
3. **Clients**: one per application. For OIDC, the issuer is `https://<KC_HOSTNAME>:8443/realms/lab`
   and discovery is at `.../realms/lab/.well-known/openid-configuration`.
4. **Trust**: clients must trust `/etc/keycloak/tls/cert.pem` while it is self-signed.

| Client | Type | Notes |
|---|---|---|
| Kubernetes API server | OIDC, public client `kubernetes` | kubespray: `kube_oidc_auth: true`, `kube_oidc_url`, `kube_oidc_client_id`, `kube_oidc_ca_file`, `kube_oidc_username_claim`, `kube_oidc_groups_claim: groups` (add a *Group Membership* mapper). Users log in with [kubelogin](https://github.com/int128/kubelogin). The issuer must be https. |
| Semaphore UI | OIDC, confidential client | `oidc_providers` in `/etc/semaphore/config.json`, redirect URI `http://<semaphore>:3000/api/auth/oidc/<provider>/redirect`. |
| VM control centre | SAML (planned) | for the `saml` sign-in mode of mcp-vm-admin-agent once it exists. |

## Operations

```bash
journalctl -u keycloak -f                                   # logs
curl -k https://127.0.0.1:9000/health/ready                 # health
sudo -u postgres pg_dump keycloak > keycloak.sql            # backup
# lost admin access: create a new temporary admin, then start again
sudo systemctl stop keycloak && sudo -u keycloak env KC_U=rescue-admin KC_P='new-password' \
  /opt/keycloak/bin/kc.sh --config-file=/etc/keycloak/keycloak.conf \
  bootstrap-admin user --username:env KC_U --password:env KC_P --no-prompt && sudo systemctl start keycloak
```

Realm export / import: `kc.sh --config-file=/etc/keycloak/keycloak.conf export --dir /tmp/kc-export`
(with the service stopped).
