# nfs-storage

Gives a Kubernetes cluster persistent storage on the lab NAS (an NFS server outside the cluster, set up by
[nfs-installer](../../nfs/nfs-installer/)): the [NFS CSI driver](https://github.com/kubernetes-csi/csi-driver-nfs)
and a **default StorageClass `nfs`**. Each PVC gets its own directory on the NAS, readable by name.

```
 pod ── PVC (storageClassName: nfs) ── csi-driver-nfs ──NFS v4.1──▶ 192.168.1.13:/srv/nfs/k8s/<namespace>/<pvc>
```

| Component | Version |
|---|---|
| csi-driver-nfs (chart and driver) | 4.13.6 (`CHART_VERSION`) |

## Install

From a machine where `kubectl` and `helm` point at the cluster (the installer VM):

```bash
cd mcp-agents/tool-box/kube/nfs-storage
./install-nfs-storage.sh --dry-run     # validate against the cluster, change nothing
./install-nfs-storage.sh               # install / upgrade, then run the write test
./install-nfs-storage.sh --test        # write test only
```

The test creates a PVC and a pod running as UID 1000 that writes `hello.txt`, then deletes them.

| Variable | Default | |
|---|---|---|
| `NFS_SERVER` / `NFS_SHARE` | `192.168.1.13` / `/srv/nfs/k8s` | the NAS export for dynamic volumes |
| `CHART_VERSION` | `4.13.6` | |
| `NAMESPACE` / `RELEASE` | `kube-system` / `csi-driver-nfs` | |
| `KUBE_CONTEXT` | current context | another cluster from the same machine |

## Using it

**A volume per app** (most cases): ask for storage; `nfs` is the default class.

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata: {name: uploads, namespace: my-app}
spec:
  accessModes: [ReadWriteMany]       # or ReadWriteOnce; NFS allows many pods on many nodes
  resources: {requests: {storage: 10Gi}}
```

It appears on the NAS as `/srv/nfs/k8s/my-app/uploads`. Recreating a PVC with the same name reuses that
directory. Deleting a PVC renames it to `archived-my-app/uploads...` instead of deleting the data; remove
archived directories on the NAS when you are sure.

**A shared folder you manage by hand** (e.g. `/srv/nfs/shared/datasets`): a static PV and a PVC bound
to it by name.

```yaml
apiVersion: v1
kind: PersistentVolume
metadata: {name: shared-datasets}
spec:
  capacity: {storage: 100Gi}
  accessModes: [ReadWriteMany]
  persistentVolumeReclaimPolicy: Retain
  storageClassName: ""
  mountOptions: [nfsvers=4.1, hard]
  csi:
    driver: nfs.csi.k8s.io
    volumeHandle: shared-datasets    # any string unique among PVs
    volumeAttributes: {server: 192.168.1.13, share: /srv/nfs/shared, subdir: datasets}
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata: {name: datasets, namespace: my-app}
spec:
  accessModes: [ReadWriteMany]
  storageClassName: ""
  volumeName: shared-datasets
  resources: {requests: {storage: 100Gi}}
```

## Things to know

* **Sizes are not enforced.** NFS has no per-directory quota: a `10Gi` PVC can fill the whole NAS. Watch
  free space on the NAS.
* **Not for databases.** PostgreSQL, Elasticsearch, etcd and similar need local-disk semantics (locking,
  fsync); run them with local storage.
* **The NAS is a single point of failure.** If it is down, pods using it hang on I/O (`hard` mounts) and
  resume when it is back.
* **Nodes do not need `nfs-common`** for this driver: it mounts from its own container. Pods that use a
  plain `nfs:` volume do need it: run the playbook
  [`kubernetes/nfs-client.yml`](../../semaphore/playbooks-library/kubernetes/nfs-client.yml) from
  Semaphore.

## Operations

```bash
kubectl get sc; kubectl get pv
kubectl -n kube-system get pods -l 'app in (csi-nfs-controller,csi-nfs-node)' -o wide
kubectl -n kube-system logs deploy/csi-nfs-controller -c nfs | tail     # provisioning errors
./install-nfs-storage.sh --uninstall   # removes driver + StorageClass; data stays on the NAS
```
