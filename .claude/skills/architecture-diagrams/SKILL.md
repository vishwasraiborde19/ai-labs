---
name: architecture-diagrams
description: Draw accurate architecture diagrams for ai-labs as Mermaid in Markdown — system context, container, deployment/infrastructure, sequence (request and delivery flows), data/log flows and state diagrams, C4-style. Use this whenever the user asks to draw, diagram, sketch, visualise or map a system, integration, flow, network, cluster or "how X talks to Y", wants a context diagram or architecture picture, or when architecture docs in arch/ or a README need a diagram added or updated — even if they just say "show me how this fits together".
---

# Architecture diagrams (ai-labs)

A diagram is only useful if it is **true** and answers **one question**. Most bad diagrams are either
invented (a box someone assumed exists) or try to show everything at once. So: gather facts first, pick
the view that answers the reader's question, draw it with consistent conventions, and say what is not
yet real.

Diagrams live in Markdown as Mermaid (renders on GitHub and in IntelliJ, diffs in git). No binary images
unless the user asks.

## 1. Gather facts before drawing

Every box, address and arrow should come from a source you can name. Sources in this repo, in order of
trust:

| Fact | Where to look |
|---|---|
| Cluster nodes, roles, IPs | `mcp-agents/tool-box/semaphore/playbooks-library/kubernetes/inventories/proxmox01/hosts.yaml` |
| k8s version, containerd mirrors | `.../proxmox01/kubespray-vars.yml` |
| Other VMs (Harbor, …) | `.../playbooks-library/registry/inventories/lab/hosts.yaml`, installer READMEs under `mcp-agents/tool-box/` |
| Catalogue of systems, URLs | `command-center/src/main/resources/seed-catalog.yml` |
| MCP agent endpoints | `~/.claude.json` / `claude mcp list` (`http://<ip>:8765/mcp`) |
| Live services, LB IPs, pods | `kubectl get nodes -o wide`, `kubectl get svc -A`, `kubectl -n <ns> get deploy,pvc` (via the installer MCP agent) |
| Current state of the whole lab | `arch/README.md` (update it, don't fork it) |

If something can't be verified, either leave it out or draw it dashed and label it "unverified" — never
present a guess as fact. Distinguish **applied** (running now) from **configured in the repo but not
applied** from **planned**.

## 2. Pick the view

| Reader's question | View | Mermaid |
|---|---|---|
| What is this system, who uses it, what does it depend on? | **Context** (C4 L1) | `flowchart LR` |
| What runs where inside it, and how do the parts talk? | **Container** (C4 L2) | `flowchart` with `subgraph` per host/namespace |
| Which VM / node / namespace / IP hosts what? | **Deployment** | `flowchart TB` with nested subgraphs |
| What happens, in order, for one request or delivery? | **Sequence** | `sequenceDiagram` |
| Where does data (logs, metrics, images) go and where is it stored? | **Data flow** | `flowchart LR` |
| What states does a thing move through? | **State** | `stateDiagram-v2` |

One question per diagram. If a context diagram passes ~20 nodes, split it (e.g. "delivery path",
"storage path", "observability path") and keep the context view at system level.

## 3. Conventions (use the same everywhere in ai-labs)

- **Arrows show who initiates**, labelled with *what* and *how*: `dev -- "docker push (HTTP :80)" --> harbor`.
  Unlabelled arrows are almost always a sign the relationship isn't understood.
- **Solid = applied and working. Dashed (`-.->`) = configured but not applied, planned, or broken.**
  Put the reason in the label: `-. "image pull over HTTP<br/>needs containerd mirror (pending)" .->`.
- **Node text**: name, then address/port, then the 1–3 facts that matter
  (`"Harbor v2.15.5<br/>192.168.1.173 :80 (HTTP)<br/>data on /data 60G"`). No credentials, ever.
- **Shapes**: people `(["…"])`, external systems/stores `[("…")]`, MCP/agents `{{"…"}}`, everything else
  `["…"]`. Group by host or trust boundary with `subgraph ID["Title"]`.
- **Legend**: state the solid/dashed rule in the text right above the diagram.
- Every diagram gets a one-line caption saying what question it answers and an "as of" date in the doc.

## 4. Mermaid that renders (gotchas)

- Always quote labels: `id["text (with parens) / slashes: ok"]`. Unquoted `()`, `[]`, `{}`, `:` , `;`,
  `#` break parsing.
- Line breaks: `<br/>`. No raw newlines inside a label.
- Don't use `end` (any case) as a node id; avoid ids with dashes or dots — use `harbor`, `kubeNode1`.
- Link to a subgraph by its id (`pve -.- K8S`) — fine in flowcharts; a subgraph id must differ from every node id.
- `&` fans out: `sem -- "SSH" --> cp & wk & nas`.
- Dotted with label: `a -. "text" .-> b`; thick: `a == "text" ==> b`.
- `sequenceDiagram`: `participant H as Harbor 192.168.1.173`; `alt/else/end`, `Note over A,B: …`;
  `autonumber` for steps people will reference.
- Prefer `flowchart` with C4 conventions over Mermaid's `C4Context` (experimental; layout is poor).
- Keep `%%` comments for section markers; they don't render.

Templates for each view, filled with ai-labs examples: `references/templates.md`. Read it before drawing a
view you haven't drawn in this session.

## 5. Check before handing over

1. Every node and arrow traces to a source from step 1 (or is marked unverified/planned).
2. Labels quoted, ids simple, no `end` ids — re-read the block once purely for syntax.
3. If Node is available, validate: `npx -y @mermaid-js/mermaid-cli -i doc.md -o /tmp/out.svg`
   (mmdc renders each mermaid block; an error names the line). If not, say the diagram was not
   render-tested.
4. No secrets, tokens or passwords anywhere in the diagram or surrounding text.
5. The doc says what changed and when ("State as of 2026-11-14").

When updating an existing diagram, change it in place in `arch/` rather than adding a second competing
picture, and move items between dashed and solid as work is applied. For where diagrams sit in the docs
and how decisions are recorded, see the `architecture-docs` skill.
