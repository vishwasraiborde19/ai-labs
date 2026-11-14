---
name: architecture-docs
description: Write and maintain ai-labs architecture documentation — the arch/ folder (system overview, systems inventory, flows, open items), Architecture Decision Records (ADRs), design docs for new systems or integrations, and architecture/design reviews (quality attributes, failure modes, trade-offs, risks). Use this whenever the user asks to document architecture, record or explain a decision ("why did we choose X"), write a design/proposal/RFC, review a design, plan a new integration, update docs after infrastructure changed, or asks "what does the lab look like now" — even if they don't say "architecture".
---

# Architecture documentation (ai-labs)

Architecture docs exist so that someone (including a future Claude session) can answer three questions
without reading every repo: **what exists now, why is it like this, and what is about to change.** Keep
them true and current rather than complete; a short accurate page beats a long stale one.

Diagrams are made with the `architecture-diagrams` skill; this skill decides what documents exist, what
goes in them and how they stay current.

## Where things go

```
arch/
├── README.md            current state: overview, context diagram, flows, systems table, open items
├── adr/
│   ├── README.md        index: number, title, status, date
│   └── NNNN-kebab-title.md
└── designs/
    └── YYYY-MM-<topic>.md   design docs for things being built or proposed
```

- **Runbooks** (how to operate one system) stay next to that system (`mcp-agents/tool-box/<system>/`);
  arch docs link to them instead of copying commands.
- **Component READMEs** say what one project does and how to build/run it; `arch/` says how projects fit
  together. Link both ways.
- One source of truth per fact: IPs and versions in the systems table, not repeated in prose across files.

## arch/README.md — the current-state page

Sections, in this order (keep existing headings if the file already has them):

1. **Header** — one paragraph on scope and `State as of YYYY-MM-DD`, plus the legend (solid = applied,
   dashed = configured but not applied / planned).
2. **Context diagram** — who talks to what.
3. **Key flows** — one diagram each for the paths people ask about (image delivery, logs/storage,
   provisioning, observability, sign-in).
4. **Systems table** — system | address | role | managed by (link to installer/playbook/runbook).
   Retired systems stay in the table marked **Retired** with what replaced them, so old references make sense.
5. **Open items** — item | effect until done | how. Remove rows when done (the ADR/commit keeps history).

Update it in the same change as the infrastructure change it describes. When you finish work that
changes the lab (new VM, new integration, address change, applied pending item), update the systems
table, flip dashed→solid in diagrams, adjust open items and the date — and tell the user you did.

## ADRs — record decisions, especially the surprising ones

Write an ADR when a choice is costly to reverse, non-obvious, or likely to be questioned later: a
technology choice, a deliberate trade-off (plain-HTTP Harbor), a constraint discovered the hard way
(kubespray v2.23 needs ansible-core 2.14.11), or a rejected alternative people will propose again.

Rules that keep ADRs useful:
- Number sequentially (`0001-…`), never renumber; title is the decision ("Run Harbor over plain HTTP on a
  dedicated VM"), not the topic.
- Status: Proposed → Accepted → (Deprecated | Superseded by NNNN). Don't rewrite an accepted ADR's
  decision; supersede it with a new one and link both ways.
- Context states the forces honestly (constraints, what broke, numbers). Consequences include the bad
  ones and what we do about them.
- Add it to `arch/adr/README.md`.

Template: `references/adr-template.md`.

## Design docs — before building something non-trivial

For a new system, integration or significant change, write `arch/designs/YYYY-MM-<topic>.md` before or
while building, and get the user's agreement on it. Template and the questions each section must answer:
`references/design-doc-template.md`. Keep it proportional — a page for a small integration, more for a
new platform component. When built, link it from `arch/README.md`; decisions inside it that matter long
term become ADRs.

## Reviewing a design or an existing architecture

Walk these lenses and report only what matters for this lab (it's a homelab: proportionate, not
enterprise theatre):

| Lens | Ask |
|---|---|
| Failure modes | What breaks if each box dies? (node, NAS, Harbor, Semaphore, observability VM) Single points of failure, blast radius, recovery steps and time |
| Data | Where does state live, is it backed up, can it be restored, retention? (Longhorn replicas all on nas-server = NAS loss is data loss) |
| Security | Trust boundaries, secrets flow, exposed ports, authn/authz — see `security-best-practices` |
| Operability | Can it be rebuilt from the repo? Monitored? Logs findable? Runbook exists? |
| Evolution | What's the upgrade path (k8s 1.27 EOL, Harbor versions)? What is pinned and why? |
| Simplicity | Is there a simpler design with the same outcome? What can be removed? |

Output: a short summary verdict, then findings as **risk** (what can go wrong, likelihood × impact in
words), **recommendation**, **effort**; separate "accepted lab trade-offs" from real gaps.

## Writing style

- Lead with the answer; state facts with their source; date anything that will change.
- Tables for inventories and comparisons, diagrams for relationships, prose for reasons.
- Concrete over generic: "Longhorn replicas all on nas-server (196 GiB)" not "storage is centralised".
- No secrets, tokens or passwords — say where they live ("password manager: Lab / Harbor") instead.
- Relative links between docs so they work on GitHub and in IntelliJ.

## Before you finish

1. Facts verified against the sources listed in `architecture-diagrams` (inventories, kubectl, configs).
2. `State as of` date updated; dashed/solid and open items reflect reality.
3. New ADRs indexed; superseded ones linked.
4. Links resolve (`grep -o '](\.\?\.\?/[^)]*)' file` and check the paths exist).
5. Mention to the user which docs changed and what's still unverified.
