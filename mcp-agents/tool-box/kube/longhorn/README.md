# longhorn

Persistent volumes for the Kubernetes cluster, managed from the **Longhorn UI**, with all data on the
storage node `nas-server` (192.168.1.13) and its 200 GB disk. Longhorn is the default StorageClass.

```
 app pod (any worker) ── Longhorn engine (same node) ──network──▶ replica: nas-server:/var/lib/longhorn
                                                                          (200 GB disk, /dev/sdb)
 browser ──▶ Longhorn UI http://192.168.1.240/   volumes, snapshots, backups, resize, space per disk
 Grafana ──▶ "Longhorn Monitoring & Backups"     via cluster-monitoring
```

| Component | Version |
|---|---|
| Longhorn (chart and app) | 1.12.2 (`CHART_VERSION`): the newest that supports Kubernetes 1.27 (1.13 needs 1.34) |

## How the storage node works

`nas-server` is a Kubernetes **node** (Longhorn only stores data on nodes), but a storage-only one:

* **Taint** `node-role.kubernetes.io/storage=true:NoSchedule`: apps never run on it. Longhorn's own
  components tolerate it.
* **Label** `node.longhorn.io/create-default-disk=true`: with `createDefaultDiskLabeledNodes`, it is the
  only node where Longhorn creates a disk, so every replica lands on it.
* **One replica per volume** (`defaultReplicaCount: 1`). `nas-server` is therefore a single point of
  failure, like any single NAS: if it is down, volumes are unavailable until it is back. Use backups.

Both are set in the inventory ([hosts.yaml](../../semaphore/playbooks-library/kubernetes/inventories/proxmox01/hosts.yaml),
`node_labels` / `node_taints`), so kubespray applies them when it adds the node.

## Setup, in order

1. **Prepare `nas-server`** (done for 192.168.1.13): 4 GB RAM, a data disk mounted on
   `/var/lib/longhorn` (in `/etc/fstab` by UUID), and Semaphore's SSH key in `~vishwas/.ssh/authorized_keys`
   with `.13` in Semaphore's `known_hosts`.
2. **Repository**: Semaphore checks out `main` from GitHub, so `hosts.yaml` with `nas-server` must be
   there (merge the branch, or point the Semaphore repository at the branch for this run).
3. **Semaphore**, in project `proxmox01`:
   1. *Linux: static IP* with CLI args `--limit=nas-server` (pins 192.168.1.13; skip if the router reserves it).
   2. *Kubespray: add workers* with CLI args `--limit=nas-server`. Adds the node with its label and taint.
   3. *K8s: Longhorn prerequisites*: `open-iscsi`, `nfs-common`, `cryptsetup`, `iscsid` on every node.
      (Re-run `semaphore/bootstrap.py` once to create this template.)
4. **Install Longhorn** from the installer VM:
   ```bash
   cd mcp-agents/tool-box/kube/longhorn
   ./install-longhorn.sh --dry-run
   ./install-longhorn.sh          # install / upgrade, then a write test
   ```
5. **Dashboard**: re-run `cluster-monitoring/grafana-dashboards.sh` on the Grafana VM.

| Variable | Default | |
|---|---|---|
| `STORAGE_NODE` | `nas-server` | must exist, be Ready and carry the create-default-disk label |
| `UI_IP` | `192.168.1.240` | MetalLB address for the UI; empty = any free one |
| `UI_ALLOW` | `192.168.1.0/24` | CIDRs that may open the UI (`loadBalancerSourceRanges`). **The UI has no login**: narrow it to your admin machines |
| `CHART_VERSION` | `1.12.2` | |
| `WAIT_FOR_NODE` | `1` | `0` installs before the storage node joins: the UI works, volumes cannot be created until the node (and its disk) arrives |

## Using it

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata: {name: data, namespace: my-app}
spec:
  accessModes: [ReadWriteOnce]       # ReadWriteMany also works (Longhorn serves it over NFS)
  resources: {requests: {storage: 10Gi}}   # enforced: the volume is a 10 GiB block device
```

`longhorn` is the default class, so `storageClassName` can be left out. Databases are fine on Longhorn
(block devices, unlike NFS).

**In the UI** (`http://192.168.1.240/`):
* *Volume*: every PVC as a volume: size, actual usage, which pod uses it, health. Expand, snapshot,
  back up, change the replica count, or delete detached volumes.
* *Node*: `nas-server`'s disk, space scheduled vs. used. Add more disks or nodes here.
* *Recurring Job*: scheduled snapshots and backups for groups of volumes.
* *Setting → Backup Target*: set an S3 bucket or NFS share to enable backups (none configured yet).

**Deleting**: `reclaimPolicy: Retain`, so deleting a PVC keeps the volume and its data. It shows as
*Detached* in the UI; delete it there when you are sure.

## Operations

```bash
kubectl -n longhorn-system get pods -o wide
kubectl -n longhorn-system get nodes.longhorn.io            # disks, schedulable, ready
kubectl -n longhorn-system get volumes.longhorn.io          # state, robustness, node
./install-longhorn.sh --test                                # write test only
```

On `nas-server`: `df -h /var/lib/longhorn` (space), `systemctl status iscsid`.

**Taking `nas-server` down** (Proxmox maintenance): scale down the apps using volumes first, or they hang
on I/O until it is back. `kubectl drain` will not move the data; there is no other copy.

**More redundancy later**: give each worker a disk, label it `node.longhorn.io/create-default-disk=true`
(or add the disk in the UI), and raise *Number of Replicas* per volume or `defaultReplicaCount`.
