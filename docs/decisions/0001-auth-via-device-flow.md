# ADR-0001 · OAuth via Device Authorization Grant

- **Status**: Accepted (2026-07)
- **Supersedes**: PRD §5.2 (AppAuth browser flow), Transport Design §5.10 D9
- **Amends**: PRD D9, Transport Design D8/D9

## Context

Data traffic is accelerated through an in-app, userspace WireGuard + SOCKS chain
(EPIC-5). In the target deployment the acceleration SOCKS lives *inside* the WG
LAN, and Google's authorization page may be **unreachable** from the phone's
network without the tunnel. So the first interactive login must also traverse the
tunnel — otherwise the app can never connect.

The original plan (PRD §5.2) used AppAuth with a system browser / Custom Tab. That
traffic goes through the **system network stack**, which the userspace tunnel
(loopback SOCKS only) cannot reach. Two workarounds were evaluated and rejected:

1. **Embedded WebView** — routable through the app's proxy, but Google blocks
   embedded user-agents (`disallowed_useragent`). The block is content-layer
   (UA/JS/TLS-fingerprint), so proxying does not bypass it; forging the UA is
   fragile and sacrifices credential isolation, SSO and passkeys.
2. **Temporary per-app VpnService during login** — technically works (feed the
   browser's packets into the same WG netstack for ~30s), but it is architecturally
   contorted and, on Google Play, a red flag: VpnService use that doesn't match the
   app's stated core function invites review rejection.

## Decision

Use the **OAuth 2.0 Device Authorization Grant** (RFC 8628; Google's "TV and
Limited-Input Device" flow) for Google sign-in — the same model as `gh auth login`.

1. The phone POSTs to `oauth2.googleapis.com/device/code` → gets a `user_code`
   and a `verification_url`.
2. The user approves in **any browser that can reach Google** — e.g. the machine
   on the far end of the tunnel, or a second device.
3. The phone polls `oauth2.googleapis.com/token` until it receives access +
   refresh tokens.

**The phone side is only two ordinary HTTPS calls (device/code + token polling),
both over the shared OkHttpClient — so both automatically ride the tunnel** once
the transport is enabled (insertion layer, Transport Design §3.0). No browser, no
WebView, no VpnService lives on the phone; to app review it is an ordinary OAuth
app.

## Consequences

- **Auth is decoupled from any on-device browser.** "Needs a browser that can
  reach Google" is satisfied off-device (second screen / tunnel far end).
- Token exchange **and** refresh are hand-rolled against the token endpoint
  (`grant_type=urn:ietf:params:oauth:grant-type:device_code` and `refresh_token`);
  AppAuth, its `RouterConnectionBuilder`, the redirect Activity and redirect scheme
  are removed.
- A Google OAuth client of type **"TVs and Limited Input devices"** is required
  (has a `client_secret`; for a public client it is not a true secret, but it must
  be supplied). Provided via gradle properties, never committed.
- **Cost**: login requires a second screen to enter the code. Accepted — the user
  confirmed a reachable second screen is always available.
- Microsoft/OneDrive (T-103) will use the equivalent MS device code flow, keeping
  both providers browserless and consistent.

## Revised decisions

- **PRD D9** — resolved: interactive auth no longer needs the system browser; the
  device-flow approval happens off-device, phone-side calls ride the tunnel.
- **Transport D8/D9** — unchanged in spirit and now unconditional: **no VpnService
  at all**, not even transiently for login.
