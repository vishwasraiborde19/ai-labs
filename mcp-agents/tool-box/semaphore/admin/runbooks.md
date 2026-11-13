# Semaphore runbooks

## Admin password recovery

**Login:** `admin`

The installer (`semaphore-installer/install-semaphore.sh`) creates the `admin` user with a random
20-character password. It prints the password **once**, at the end of the first install, and does not
store it anywhere. Re-running the installer keeps the existing password and does not print it again.
`/etc/semaphore/.dbpass` is the PostgreSQL password, not the web login.

### 1. Look for the existing password first

- Password manager entry: **Lab / Semaphore admin**.
- The output of the first install (terminal scrollback, or the job log if it ran through an MCP job).
- If Keycloak SSO is configured for Semaphore, log in with your Keycloak account instead.

### 2. Reset the password

On the Semaphore VM (e.g. `192.168.1.42`), as a user with sudo:

```bash
NEW_PW=$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-20)   # or choose your own
sudo -u semaphore semaphore user change-by-login --login admin --password "$NEW_PW" \
  --config /etc/semaphore/config.json
echo "$NEW_PW"
```

No restart is needed.

If the `admin` user no longer exists (`sudo -u semaphore semaphore user list --config /etc/semaphore/config.json`),
create it again:

```bash
sudo -u semaphore semaphore user add --admin --login admin --name "Administrator" \
  --email admin@semaphore.local --password "$NEW_PW" --config /etc/semaphore/config.json
```

### 3. Verify

```bash
curl -s -o /dev/null -w '%{http_code}\n' -H 'Content-Type: application/json' \
  -d "{\"auth\":\"admin\",\"password\":\"$NEW_PW\"}" http://127.0.0.1:3000/api/auth/login
```

`204` means the login works. `401` means the password was not changed.

### 4. Afterwards

- Save the new password in the password manager under **Lab / Semaphore admin**.
- Existing API tokens (for example, the one used by `playbooks-library/kubernetes/semaphore/bootstrap.py`)
  keep working. A password reset does not revoke them.
