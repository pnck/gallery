# PERF-BENCH-TODO — bench-tunable design knobs

Every entry below is a design choice whose current value was picked by reasoning,
not measurement. Before changing any of them, build the benchmark first (§1) —
no more feel-based tuning (the 64K→256K smoltcp buffer change was exactly that).

## 1. Benchmarks to build FIRST (the anchor for everything else)

- [ ] **Driver throughput bench (host)** — extend the E2E echo harness
  (`rust/src/wg.rs::end_to_end_tcp_echo_through_live_tunnel`) with a bulk
  transfer mode: N MiB through a live tunnel against the scripted server,
  report MiB/s + packets/s + allocs. Zero network variables — pure
  driver/splice/smoltcp cost.
- [ ] **Device upload timing protocol** — one real 50–100 MiB upload via the
  tunnel on device; log per-chunk wall time (already have per-chunk progress).
  Compare direct-vs-tunnel, H1.1-parallel-vs-H2-single.
- [ ] **Thread/fd inventory under load** — scroll a cloud album + run a bulk
  upload; capture `/proc/self/status` thread count and fd count. Feeds the
  reactor-rewrite decision (§3).

## 2. Throughput / latency knobs

| Knob | Current value | Picked by | Decision it feeds |
|---|---|---|---|
| smoltcp socket buffers (`wg.rs open_socket`) | 256 KiB/direction | feel | window vs per-conn ceiling; compare against 1 MiB + H2-single-conn |
| `CONN_BUF_CAP` (app↔driver buffer) | 256 KiB | feel | bufferbloat vs stall resistance |
| `PARALLEL_UPLOADS` (`UploadBatchProcessor`) | 2 | feel | 3–4 may be better on high-RTT paths; needs device timing |
| upload client protocol | HTTP/1.1 only | reasoning | verify H1.1-parallel actually beats H2 + big window on the bench |
| `ResumableUploader.CHUNK_SIZE` | 8 MiB | reasoning | RTT-bound paths may prefer 4 MiB (faster recovery granularity) vs 16 MiB (fewer round trips) |
| splice copy buffer (`socks.rs copy_half`) | 16 KiB | feel | syscall/copy amortization; measure with driver bench |
| upload pool size | 6 conns | feel | parallelism ceiling |
| shared pool size | 15 conns | OkHttp default ×3 | Coil + API concurrency |
| thumbnail `=s` size (`ProviderUriFetcher`) | 512 px | grid guess | decode time vs bytes; measure on low-end device |
| MTU default | 1280 | safety | per-network tuning; user-configurable already |

## 3. Structural choices pending a benchmark verdict

- [ ] **Reactor rewrite (thread-per-conn → mio event loop)** — current: 2
  threads/conn + driver + reader + acceptor, plus ConnShared buffers/condvars.
  Target: ONE thread polling all fds + smoltcp (the canonical mio+smoltcp form).
  Collapses the thread count, removes the double buffering and the mailbox
  Datagram hop. Big rewrite — only after §1 numbers show threads/copies are a
  real cost, and only with the E2E test guarding behavior.
- [ ] **Bounded queues** — mailbox (unbounded mpsc), `TunDevice.inbound/outbound`
  (VecDeque), Kick flow. Pick caps (e.g. 512 packets, drop-oldest — legal for
  UDP) after measuring normal-water levels.
- [ ] **Mailbox Datagram alloc churn** — one `Vec` alloc per WG packet. If the
  driver bench shows allocator pressure: buffer pool or `Bytes`-style reuse.
- [ ] **WgOnly DNS strategy** — currently: per-dial TCP+UDP resolver race on
  scratch threads + local-resolve fallback (deliberate DNS LEAK). Decide:
  single in-tunnel resolver with hard failure, or keep fallback as an explicit
  opt-in setting. A correctness/privacy decision, not a perf one — but its
  per-dial thread spawn IS measurable.
- [ ] **Idle wake budget** — reader thread timeout / `IDLE_WAIT_CAP` 250 ms.
  Measure actual idle wakeups/s on device (battery), tune to keepalive needs.

## 4. Security/correctness hygiene (not perf, same backlog)

- [ ] `upload_sessions.session_uri` stored in Room in plaintext — session URIs
  are capability URLs; move to EncryptedSharedPreferences or document the risk.
- [ ] evictAll on every route rebind kills ALL pooled connections (incl. healthy
  auth ones) — safe but blunt; per-host eviction if it ever shows in traces.

## Done

- [x] fd-level resume positioning (`FileChannel.position` + processor seek
  pre-flight) — replaced `InputStream.skip` read-and-discard (quadratic IO on
  unseekable providers). 2026-07.
