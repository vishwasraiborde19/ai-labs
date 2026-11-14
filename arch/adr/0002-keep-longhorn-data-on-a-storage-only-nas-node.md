# 0002. Keep Longhorn data on a storage-only NAS node

- **Status:** Accepted
- **Date:** 2026-11-13
- **Related:** [longhorn](../../mcp-agents/tool-box/kube/longhorn/), [nfs-installer](../../mcp-agents/tool-box/nfs/nfs-installer/), [cluster inventory](../../mcp-agents/tool-box/semaphore/playbooks-library/kubernetes/inventories/proxmox01/hosts.yaml)

## Context

Workloads need persistent volumes (first use: application log files). The worker VMs have small root
disks; the NAS VM (192.168.1.13) has a 196 GiB data disk and already serves NFS outside Kubernetes.
Longhorn needs its data on a Kubernetes node.

## Decision

nas-server joins the cluster as a **storage-only node** (added with kubespray scale): labelled
`node.longhorn.io/create-default-disk=true`, tainted `node-role.kubernetes.io/storage=true:NoSchedule`
so workloads stay off it, which Longhorn tolerates. Longhorn 1.12.2 is the default StorageClass, with
its disk only on nas-server and a default replica count of 1. The NFS export and the `nfs` StorageClass
remain for ReadWriteMany needs.

## Alternatives considered

| Option | Why not |
|---|---|
| Longhorn replicas on worker disks | small root disks; replicas would compete with workloads |
| NFS StorageClass only | no snapshots, per-volume UI, or block semantics; NFS locking issues for some apps |
| Ceph / Rook | far too heavy for the lab's hardware and attention |
| hostPath / local PVs | data tied to one worker; lost on rebuild |

## Consequences

- **Good:** one place with capacity for all volumes; Longhorn UI (192.168.1.240) for snapshots and resize;
  workers stay disposable.
- **Bad:** **nas-server is a single point of failure for every volume** — 1 replica, all on one disk. If
  the node is down, pods with volumes can't start; if the disk is lost, data is lost.
- **Bad:** volumes are ReadWriteOnce: deployments using them need `strategy: Recreate` and one replica.
- **Follow-ups:** configure a Longhorn backup target that is not on nas-server (e.g. NFS/S3 on another
  host) and recurring backups for volumes that matter; revisit replica count if a second storage disk appears.
