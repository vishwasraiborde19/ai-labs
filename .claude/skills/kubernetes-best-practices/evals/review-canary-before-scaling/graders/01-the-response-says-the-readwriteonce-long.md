---
type: llm
focus: trace
weight: 1
---

The response says the ReadWriteOnce Longhorn PVC cannot be shared by 3 replicas (Multi-Attach / stuck rollout) and proposes a fix such as a StatefulSet with volumeClaimTemplates, an RWX nfs volume, or logging to stdout
