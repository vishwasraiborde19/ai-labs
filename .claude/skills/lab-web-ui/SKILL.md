---
name: lab-web-ui
description: Build and change the web UIs of ai-labs apps the house way — plain HTML/CSS/JS (no framework, no build step) served by Spring Boot from src/main/resources/static/ui, a shared CSS token set with dark mode, a DOM helper instead of innerHTML, one fetch wrapper for /ui/api, hash routing, toasts and dialogs. Use this whenever adding or editing a page, view, dashboard, console, form, table or button in command-center, mcp-vm-admin-agent, mcp-orchestrator-agent, mcp-vm-worker-agent, infra-canary or a new lab app, or when the user asks for "a UI for X", "a page to manage Y" or a screen in any ai-labs project — even if they don't say "frontend".
---

# Lab web UIs (ai-labs)

Every lab app ships its own small admin UI, and they all work the same way so that any of them can be
read, fixed and extended by anyone (or any Claude session) without a toolchain. Follow the house pattern;
introduce a framework only if the user asks for one.

Reference implementations to copy from, in order of size:
`command-center/src/main/resources/static/ui/` (routing, catalog, dialogs, read-only mode),
`mcp-agents/vm/mcp-vm-admin-agent/src/main/resources/static/ui/` (fleet, terminal, jobs),
`mcp-agents/mcp-orchestrator-agent/src/main/resources/static/ui/`,
`infra-canary/src/main/resources/static/index.html` (single-file console with live polling).

## The house pattern

| Concern | Convention | Why |
|---|---|---|
| Files | `static/ui/index.html`, `app.css`, `app.js` (+ one JS file per big feature) | served by Spring Boot as-is; no npm, no bundler, works offline in the lab |
| Entry | `/ui/` (redirect `/` → `/ui/` if the app has no other root) | one predictable URL per app |
| API | JSON under `/ui/api/...` on the same origin, every request sends `X-<App>: 1` and the server rejects mutating calls without it | cheap CSRF guard: browsers can't add custom headers cross-site without CORS |
| DOM | build elements with the `h(tag, attrs, ...kids)` helper; text via `textContent` | never `innerHTML` with data — hostnames, logs and catalog entries are untrusted (XSS) |
| State | one `S` object; `render()` rebuilds the current view from it | simple to reason about at this size |
| Routing | `location.hash` (`#/catalog`, `#/system/<id>`), `hashchange` → `render()` | deep links and back button with no server routes |
| Styling | CSS custom properties on `:root`, redefined for dark mode | consistent look; dark mode for free |
| Feedback | `toast(msg, bad)` in an `aria-live` region; `<dialog>` for forms/confirms | one place for success/error messages |
| Storage | `localStorage` only for per-user conveniences (filters, favourites), wrapped in try/catch, keys prefixed (`cc.`) | private mode throws; real data lives on the server |
| Polling | `setInterval` tracked in `S.timers`, cleared on view change, paused when `document.hidden` | no runaway timers or background load |

## Starting a new UI

Copy `assets/starter/` (index.html, app.css, app.js) into `src/main/resources/static/ui/`, rename the
header and app prefix, then add views. The starter already has: tokens + dark mode + base components
(buttons, inputs, cards, tables, chips, badges, toast, dialog), `h()`, `api()` with `ApiError`, hash router,
`toast`, `confirmDanger`, polling helpers, empty/loading/error states and a skip link.

Server side (Spring Boot):
```java
@Controller class UiRoot { @GetMapping("/") String root() { return "redirect:/ui/"; } }
```
```properties
spring.web.resources.cache.cachecontrol.no-cache=true   # lab: always fresh after a redeploy
```
API controllers live under `/ui/api`, return JSON, use proper status codes and `{"error": "..."}` (or
ProblemDetail) bodies so `api()` can show the message — see the `api-best-practices` skill. Check the
custom header in a filter for POST/PUT/PATCH/DELETE.

## Writing views

```js
function viewSystems() {
  const list = h('div', { class: 'list' });
  if (!S.systems) return h('p', { class: 'muted' }, 'Loading…');
  if (!S.systems.length) return h('div', { class: 'empty' }, 'No systems yet. ', h('button', { class: 'primary', onclick: openAdd }, 'Add one'));
  for (const s of S.systems) list.append(
    h('div', { class: 'card' },
      h('h3', {}, s.name),                         // text, never markup
      h('span', { class: 'chip' }, s.kind),
      h('a', { href: '#/system/' + encodeURIComponent(s.id) }, 'Open')));
  return list;
}
```

- Every view handles **loading, empty, error and data** states explicitly.
- Disable a button while its request runs, re-enable in `finally`; report errors with `fail(e)`.
- Destructive actions go through `confirmDanger(what, consequence)` and use `button.primary.danger`.
- Numbers get units and context (`312 / 384 MiB`, `3 s ago`); timestamps relative with the absolute in `title`.
- New colours become tokens in `:root` (light and both dark blocks), never inline hex.

## Before handing over

1. No `innerHTML`/`insertAdjacentHTML`/`outerHTML` with data: `grep -n "innerHTML" static/ui/*.js` — only
   clearing (`= ''`) or constant markup is acceptable.
2. Works in light and dark (toggle OS theme or set `document.documentElement.dataset.theme`).
3. Usable at 375 px wide: no horizontal page scroll; tables scroll inside their own wrapper.
4. Keyboard: every action reachable with Tab, visible focus, Esc closes dialogs.
5. Errors from the API show the server's message, not `[object Object]`.
6. A MockMvc test asserts the page is served (`GET /ui/` → 200, contains the app title) and that
   mutating API calls without the custom header get 403.
7. If a browser tool is available (claude-in-chrome or the built-in browser), open the page and look at it;
   otherwise say it was not visually checked.

How the UI should *behave* for operators (states, dangerous actions, density, accessibility) is in the
`ui-ux-guidelines` skill — read it for any new screen.
