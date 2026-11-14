# 0006. Build fault injection into infra-canary, off by default

- **Status:** Accepted
- **Date:** 2026-11-14
- **Related:** [infra-canary README, chaos section](../../infra-canary/README.md), `infra-canary/src/main/java/com/ailabs/canary/chaos/`

## Context

The lab needs a way to see how Kubernetes, Longhorn and monitoring react to failures — failing health,
NotReady pods, liveness restarts, memory pressure and OOM kills, CPU load, latency, errors, a full
volume — without a chaos platform or touching real apps.

## Decision

infra-canary 0.2.0 contains its own fault injection: a console at `/` and an API under `/chaos`.
Faults are **real** (memory allocated, CPU burned, files written, probe state changed) except mock
gauges, which only publish numbers. Changes are refused unless `canary.chaos.enabled=true` (set in its
ConfigMap; default false), optionally require an `X-Chaos-Token` compared in constant time, can revert
themselves after N seconds, and are logged at WARN. The console and `/chaos` are never affected by
faults, and a separate LoadBalancer Service (192.168.1.241) with `publishNotReadyAddresses: true` keeps
the console reachable while the pod is NotReady.

## Alternatives considered

| Option | Why not |
|---|---|
| Chaos Mesh / Litmus | cluster-wide privileges and CRDs for a single-app experiment surface |
| Manual `kubectl exec stress …` | not repeatable; image has no tools; no auto-revert |
| Faults in real apps | risk to things people use |

## Consequences

- **Good:** repeatable, self-reverting experiments from a browser; metrics `canary_chaos_active{fault}`
  show when a fault was on.
- **Bad:** an endpoint that can crash its own pod — must never be enabled in shared or production-like
  namespaces without the token.
- **Follow-ups:** deploy 0.2.0 and the console Service (open item); scrape its metrics.
