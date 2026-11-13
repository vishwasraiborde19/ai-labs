# nfs-installer

One script that turns a fresh Ubuntu 22.04 VM into the lab's NAS: an NFS server outside Kubernetes whose
shares pods mount (through [kube/nfs-storage](../../kube/nfs-storage/)) and other VMs can mount too.
The data lives on this VM, so it survives rebuilding or deleting a cluster.

> **Lab status:** `proxmox01` now uses [Longhorn](../../kube/longhorn/) with its data on the storage node
> `nas-server` (192.168.1.13), which no longer runs NFS. This tool stays for NFS shares elsewhere.


```
 NAS VM (192.168.1.13)                                   Kubernetes nodes / VMs
 /srv/nfs/k8s     one directory per PVC  ──NFS v4.1──▶   csi-driver-nfs ─▶ pod /data
 /srv/nfs/shared  hand-managed folders   ──NFS──────▶    static PVs, other VMs
 exported only to NFS_CLIENTS
```

| Component | Source |
|---|---|
| `nfs-kernel-server` (NFS v3, v4.0-4.2) | Ubuntu |
| `linux-virtual` kernel, only if the running kernel has no `nfsd` | Ubuntu |

## Install

```bash
git clone https://github.com/vishwasraiborde19/ai-labs
NFS_CLIENTS="192.168.1.20 192.168.1.21 192.168.1.51 192.168.1.104 192.168.1.198 192.168.1.142" \
  ./ai-labs/mcp-agents/tool-box/nfs/nfs-installer/install-nfs-server.sh
```

Run it as a user with sudo. **Ubuntu cloud images on Proxmox run the minimal `-kvm` kernel, which has no
NFS server module**: the first run then installs `linux-virtual`, makes GRUB prefer it
(`/etc/default/grub.d/90-ai-labs-nfs.cfg`), and exits with code 3. Reboot and run it again with the same
variables.

| Variable | Default | |
|---|---|---|
| `NFS_CLIENTS` | *(required)* | IPs or CIDRs allowed to mount: the Kubernetes nodes, plus any VM that needs the shares |
| `NFS_SHARES` | `k8s shared` | directories under `NFS_ROOT`, each exported |
| `NFS_ROOT` | `/srv/nfs` | |
| `DATA_DISK` | empty | e.g. `/dev/sdb`: put the shares on a dedicated disk (below) |
| `NFS_ROOT_SQUASH` | `0` | `0`: root in a pod stays root on the share, which most containers need. `1`: root becomes `nobody` |
| `NFS_THREADS` | `16` | nfsd threads |

The script is idempotent: re-running rewrites `/etc/exports.d/lab.exports` from the variables and keeps
the data. Add your own exports in `/etc/exports.d/local.exports`.

### Adding a data disk

The shares start on the system disk. To move them to their own disk:

1. In Proxmox, add a disk to the VM (*Hardware → Add → Hard Disk*, e.g. 200 GB, *Discard* on).
2. Find it with `lsblk` (e.g. `sdb`, no partitions), then re-run with `DATA_DISK=/dev/sdb`.

The script formats the disk as ext4 (label `nas-data`) **only if it is blank**, and refuses a disk that
has partitions or another filesystem. It stops NFS, copies the existing shares onto the disk, mounts it on
`/srv/nfs` through `/etc/fstab` (by UUID), and keeps the old directory as `/srv/nfs.pre-disk.<date>`
until you delete it. Pods using the shares pause during the move and resume afterwards.
`nfs-server` gets `RequiresMountsFor=/srv/nfs`, so if the disk is missing at boot NFS stays down instead
of serving empty directories from the system disk.

## What it sets up

* `nfs-server` unit, NFS v3 and v4.x; settings in `/etc/nfs.conf.d/lab.conf`.
* `/srv/nfs/<share>` for each share, mode `0777` (pods run as many different UIDs).
* Export options `rw,sync,no_subtree_check,no_root_squash`, to `NFS_CLIENTS` only. `no_root_squash`
  means any root on an allowed client is root on the shares: keep the list to machines you control.
* If ufw is active: 2049/tcp and 111 allowed from `NFS_CLIENTS`.

## Operations

```bash
sudo exportfs -v                       # what is exported, to whom
showmount -e localhost
sudo du -sh /srv/nfs/k8s/*/*           # space per namespace / PVC
sudo find /srv/nfs/k8s -maxdepth 1 -name 'archived-*'   # directories of deleted PVCs: remove when sure
journalctl -u nfs-server -f
```

**Backups**: back up the VM (or just its data disk) in Proxmox, *Datacenter → Backup*. NFS is not a
database filesystem: keep PostgreSQL, Elasticsearch and similar on local disks.
