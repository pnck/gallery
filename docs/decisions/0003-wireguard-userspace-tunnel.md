# ADR-0003: Userspace WireGuard tunnel via boringtun + smoltcp

- Status: Accepted
- Date: 2026-07-08
- Supersedes/implements: Transport Design §2 (Tier 1), PRD §8.4; builds on ADR-0002.

## Context

The accelerated path (Transport §2.1) must reach an in-LAN SOCKS5 accelerator that
is only routable *inside* the user's home/office WireGuard network. The very first
interactive login has to traverse that tunnel (there is no other route to the LAN),
so the tunnel is a hard prerequisite, not an optimisation. Two hard constraints
from AGENTS.md invariant #9: **no Android `VpnService`** and **no system TUN** — the
tunnel must live entirely in userspace/process memory.

## Decision

Implement a memory-only WireGuard tunnel in the existing `rust/` crate:

- **boringtun 0.7** — WireGuard protocol only (sans-io: `Tunn::encapsulate` /
  `decapsulate` / `update_timers`). Pure-Rust crypto, cross-compiles cleanly for
  Android via cargo-ndk.
- **smoltcp 0.13** — userspace TCP/IP. A custom `phy::Device` (`TunDevice`) whose
  "wire" is two in-memory packet queues bridges smoltcp IP packets to boringtun.
- **One driver thread** owns the `Tunn`, the UDP socket to the WG peer, the smoltcp
  `Interface` and all sockets (smoltcp is single-threaded). Application threads talk
  to it only through per-connection byte buffers (`ConnShared`, mutex+condvar) and a
  command channel — exposed as a blocking, `TcpStream`-like `TunnelStream`.

The SOCKS5 outbound dialer is abstracted over a `Conn` trait (Read + Write + clone),
so the exact same upstream SOCKS5 client handshake runs over a real socket
(Direct/SocksUpstream) or over a `TunnelStream` (WgThenSocks). Hostnames are
preserved end-to-end (remote DNS, §4.2).

The FFI surface is unchanged (the 5 primitives of Transport §2.1). `CoreConfig`
gains a `WgThenSocks` variant carrying base64 WG keys, the resolved/`host:port`
endpoint, the tunnel-interior interface CIDRs, keepalive, and the in-tunnel upstream
SOCKS address. `TransportHealth.handshake_ok` now reflects the real WireGuard
handshake in this mode.

## Data flow

```
127.0.0.1:LP  SOCKS5 inbound
  └─ Dialer::WgThenSocks
       └─ WgTunnel.dial(upstream_socks_ip:port)   → smoltcp TCP over the tunnel
            └─ boringtun encapsulate/decapsulate   ↔ UDP ↔ WG peer
                 └─ in-LAN SOCKS5 accelerator → Google / Microsoft
```

## Consequences

- **Testability without a live peer**: the WireGuard handshake + transport crypto is
  unit-tested by driving two `Tunn`s against each other in-memory
  (`wireguard_handshake_and_transport_roundtrip`). A full end-to-end test against a
  real WG peer needs infrastructure and is deferred to a device/integration lane.
- **Busy-ish driver loop**: the driver services the netstack, WG timers and app
  writes on a short (~5 ms) tick because a blocking UDP `recv` cannot be interrupted
  by an app write without a platform waker. Acceptable for MVP; a self-pipe/`mio`
  waker is a later optimisation. Documented at `TICK` in `wg.rs`.
- **ring dependency**: boringtun 0.7 pulls in `ring`, which needs a C toolchain. This
  is fine in CI (native x86_64 NDK clang) and for the arm64 host build (host-gcc),
  and is why the `.so` cross-compile stays CI-owned (ADR-0002).
- **Endpoint DNS**: the WG endpoint is resolved once, directly, at start. It is a
  public address reachable without the tunnel, so this leaks no accelerated traffic.

## Alternatives considered

- **wireproxy / boringtun-cli** as a one-shot binary: rejected — we need an in-process
  library with a narrow FFI, not a subprocess, and the FFI must stay the 5 primitives.
- **Gobley KMP bindings now**: deferred (ADR-0002); UniFFI + cargo-ndk while Android-only.
