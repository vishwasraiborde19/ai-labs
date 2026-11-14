---
name: ui-ux-guidelines
description: UX, interaction and accessibility guidelines for ai-labs operational UIs — admin consoles, dashboards, fleet and catalog views, forms, chaos/control panels and status pages used by the people running the lab. Covers information hierarchy, status and health display, loading/empty/error states, feedback, dangerous and irreversible actions, live data and polling, forms and validation, tables and density, copy/wording, responsive layout, dark mode and accessibility (WCAG AA). Use this whenever designing a new screen, reviewing or improving an existing UI, choosing how to show status or a destructive button, or when the user says a page is confusing, cluttered, ugly or hard to use — even if they only ask to "make it nicer".
---

# UI/UX guidelines for lab consoles

The people using these screens are operators in the middle of something: checking whether the lab is
healthy, fixing what isn't, or breaking things on purpose to test it. A good console answers **"is
everything OK, and if not, what and where?"** in a glance, makes the safe action easy and the dangerous
one deliberate, and never leaves the user guessing whether something happened.

Implementation conventions (files, tokens, `h()`, `api()`) are in the `lab-web-ui` skill; this one is
about behaviour and design.

## 1. Hierarchy: answer the first question first

- Top of every view: the overall state (healthy / N problems) and what needs attention, then details.
  Command-center's home and the canary console's status tiles are the pattern.
- One primary action per view (filled accent button); everything else secondary or in a menu.
- Group by what operators think in (system, host, namespace), not by implementation detail.
- Prefer showing over linking: the value, its unit, and how it compares (`312 / 384 MiB`, bar at 81%).

## 2. Status that can't be misread

- Use the badge set consistently: **ok** (green), **warn** (amber), **bad** (red), **off/unknown** (grey).
  Never colour alone — always a word (`healthy`, `failing`, `unknown`) and ideally an icon/dot, because of
  colour blindness and dark mode.
- Distinguish **failing** from **unknown/unreachable**: "no answer" is not "down" — show it grey with when
  it was last seen.
- Show freshness: "checked 12 s ago" next to anything polled; stale data (> 3 intervals) visibly dimmed
  with a warning.
- Counts that matter go in the nav or header (e.g. "Agents (2 offline)").

## 3. Every state is designed

For each view and each async piece, decide what the user sees when it is:

| State | Show |
|---|---|
| loading (first time) | "Loading…" or a skeleton in place — never an empty page |
| refreshing | keep old data, a subtle indicator; don't flash or reorder |
| empty | why it's empty + the next step ("No systems yet — **Add one**") |
| error | what failed in plain words, the server's message, and **Retry**; keep last good data if any |
| partial | which parts failed (e.g. 2 of 6 agents unreachable) without hiding the rest |
| read-only / disabled | why (tooltip or banner: "Changes only on the command center's own machine") |

## 4. Feedback for every action

- Within ~100 ms: the button reacts (disabled + "Saving…"). Within the request: success toast or inline
  error. Long work (> 2 s): progress or a job entry with status, not a spinner forever.
- Errors appear where the user is looking: next to the field for validation, in the dialog for a failed
  save, a toast for background actions. Keep the user's input on failure.
- After a change, show the new state (re-fetch) rather than assuming success.
- Toasts confirm; they don't carry information the user must act on — that goes in the page.

## 5. Dangerous actions are deliberate

Ranked by consequence:

| Consequence | Pattern |
|---|---|
| reversible, local | just do it; offer **Undo** in the toast where possible |
| disruptive but recoverable (restart, drain, break liveness, burn CPU) | confirm dialog naming the target and the effect ("Kubernetes will restart this container"), red button with the verb |
| irreversible or shared (delete volume/system, revoke token, format, crash) | type-to-confirm the resource name; say what is lost; never the default focus |

- Destructive buttons are visually separated from routine ones and use the verb ("Delete agent"), never
  "OK"/"Yes".
- Prefer auto-reverting or time-boxed changes for experiments (the canary's "revert after N s").
- Always provide the way back on the same screen ("Reset everything", "Accept traffic").
- A kill switch / control path must stay reachable while the thing it controls is broken (the canary
  console's publish-not-ready Service).

## 6. Live data without chaos

- Poll at the rate the data changes (2–5 s for live probes, 30–60 s for catalog health); pause when the
  tab is hidden; stop when leaving the view.
- Update in place; keep scroll position, focus, open dialogs and typed input across refreshes.
- Don't reorder lists under the cursor; new items appear at a predictable place.
- Timeouts on every request so a hung backend shows "not answering" instead of freezing the UI.

## 7. Forms

- Labels above inputs, always visible (placeholders aren't labels). Sensible defaults pre-filled.
- Units in the label ("revert after (s)", "MiB"); min/max on number inputs; validate on submit and on
  blur, explain the rule ("1–16384 MiB").
- Secrets: `type=password`, never echoed back, never stored in localStorage; show "set"/"not set".
- Submit with Enter, cancel with Esc; focus the first field on open and return focus on close.

## 8. Tables and density

- Operators scan: compact rows (8–10 px padding), sticky header, right-align numbers, monospace for IDs,
  IPs and hashes, truncate long values with the full text in `title` and a copy button.
- Filter/search above the table for > 15 rows; remember filters per user (localStorage).
- On narrow screens, scroll the table inside its wrapper or switch to cards — never scroll the whole page sideways.

## 9. Words

- Plain, specific, active: "Harbor is not reachable from kube-node-002" beats "Error occurred".
- Name things as the lab does (Harbor, Longhorn, nas-server, Semaphore template names).
- Buttons are verbs; headings are nouns; no jargon the user didn't introduce; numbers with units.

## 10. Accessibility and responsiveness (minimum bar)

- Contrast ≥ 4.5:1 for text, 3:1 for UI parts, in light **and** dark (tokens are tuned for this —
  don't invent colours).
- Fully keyboard-usable; visible focus (`:focus-visible`); logical tab order; skip link.
- Semantic elements: `button` for actions, `a` for navigation, `table` for tables, `dialog` for modals,
  `label` tied to inputs, `aria-live` for toasts/status, `aria-current="page"` in nav.
- Touch targets ≥ 32 px; works at 375 px wide; respects `prefers-reduced-motion`.

## Reviewing a UI

Open it (browser tool if available; otherwise read the HTML/JS) and walk: first glance (can you tell if
all is OK in 5 s?), each state from §3, each action from §4–5, keyboard-only pass, narrow width, dark
mode. Report findings as **blocker** (misleading status, unsafe destructive flow, inaccessible), **should
fix**, **polish** — each with the screen/element, what the user experiences, and the concrete change
(copy, layout, or code snippet). Point out what already works well so it's kept.
