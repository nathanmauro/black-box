# Ambient Companion

A chrome-less Black Box route plus a thin macOS shell that keeps agent activity in the corner of
the screen without becoming a notification feed.

## Route

`http://127.0.0.1:8766/companion` renders three levels:

- **Mini**: a chip with the pulse state (`connecting`, `live`, `idle`, `offline`) and the count of
  meaningful items you have not opened.
- **Compact**: active projects (a live session in the last 10 minutes, or a Decision, Handoff, or
  Observation in the last 24 hours), sorted by unseen count then recency. `River` shows every item
  across projects in time order.
- **Expanded**: one project's items, or the river, each linking to the exact event in the browse
  view. Handoff rows show the next action and open-loop count. A handoff is a baton, not a
  completion.

Escape steps down one level. Mode and seen-state persist in the browser's `localStorage`.

Only `Decision`, `Handoff`, and `Observation` events become rows. Tool-call activity only drives
the pulse. The route reads the existing stream and `/api/events` query
(`kind:decision,handoff,observation last:24h`); it adds no endpoints and never writes to Black Box.

## macOS shell

`companion/macos` is a Swift Package. It hosts the route in a floating, non-activating panel and
mirrors pulse plus unseen count into a menubar item.

```bash
cd companion/macos
swift build
swift run BlackBoxCompanion                      # default: http://127.0.0.1:8766/companion?embedded=1
swift run BlackBoxCompanion --url http://127.0.0.1:8799/companion?embedded=1
swift run BlackBoxCompanion --self-test /tmp/companion.png   # loads the page, writes a PNG, exits 0
swift test
```

The menubar menu offers Show/Hide Companion, Open Black Box, and Quit. The panel resizes as the
page changes level and remembers its position; a manual resize of the expanded panel is remembered
per mode and preferred over the page's own default size the next time that mode is entered. Links
open in the default browser. With `?embedded=1` the page paints no background, so only the chip or
card shows in the clear panel.

The shell assumes Black Box authentication is disabled on `127.0.0.1` (the default; see
[Authentication](docs/authentication.md)). Its `WKWebView` carries no credentials, so if
authentication is enabled the panel's requests to `/companion` and the APIs it reads will fail
(redirected to a login page, or 401) until authentication is turned off again or the shell is
extended to carry a token.

Design: `docs/superpowers/specs/2026-09-24-ambient-companion-design.md`.
