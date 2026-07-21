//! Userspace WireGuard tunnel: boringtun (WG protocol) + smoltcp (netstack).
//!
//! This is the Tier-1 core's phase 2 (Transport §2). It builds a memory-only
//! WireGuard tunnel — **no TUN device, no system VPN** — and lets the SOCKS5
//! inbound dial the in-LAN upstream SOCKS *through* it:
//!
//! ```text
//!   TunnelStream (blocking Read+Write)  ──►  smoltcp TCP socket
//!     └─ driver thread ─┐                        │ IP packets
//!        boringtun Tunn ◄── encapsulate/decapsulate ──► UDP socket ──► WG peer
//! ```
//!
//! smoltcp is single-threaded and poll-driven, so one **driver thread** owns the
//! `Tunn`, the UDP socket, the netstack `Interface` and all sockets. Application
//! threads talk to it only through per-connection byte buffers guarded by a
//! mutex+condvar ([`ConnShared`]) and a command channel ([`Command`]).
//!
//! Crypto (x25519 handshake, ChaCha20-Poly1305 transport) lives entirely in
//! boringtun; we only shuttle datagrams. The handshake itself is unit-tested by
//! driving two `Tunn`s against each other in-memory (see tests).

use std::collections::VecDeque;
use std::io::{self, Read, Write};
use std::net::{IpAddr, SocketAddr, UdpSocket};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant as StdInstant};

use base64::Engine;
use boringtun::noise::{Tunn, TunnResult};
use boringtun::x25519::{PublicKey, StaticSecret};
use smoltcp::iface::{Config, Interface, SocketHandle, SocketSet};
use smoltcp::phy::{Device, DeviceCapabilities, Medium};
use smoltcp::socket::{dns, tcp};
use smoltcp::time::Instant as SmolInstant;
use smoltcp::wire::{DnsQueryType, HardwareAddress, IpAddress, IpCidr, IpEndpoint};

use crate::socks::Conn;

/// Default tunnel MTU when the config leaves it unset (0). The IPv6 minimum (1280) is a
/// safe floor: the WG-encapsulated UDP datagram (inner + ~60B WG + 28B IP/UDP ≈ 1368)
/// survives paths with a reduced MTU — cellular, PPPoE, nested tunnels. The classic
/// "handshake OK but large transfers stall" is a too-large MTU: full-size data packets
/// get dropped while small requests slip through, so TCP crawls on retransmits. The MTU
/// is configurable per tunnel (WgSettings.mtu) for users who want to push it higher.
const DEFAULT_MTU: usize = 1280;
/// Scratch buffer for boringtun in/out (must fit MTU + WG framing).
const SCRATCH: usize = 2048;
/// Per-connection app↔socket buffer high-water mark (backpressure).
const CONN_BUF_CAP: usize = 256 * 1024;
/// Fallback DNS resolvers when the WG config specifies none (design semantic).
const DEFAULT_DNS: [&str; 2] = ["1.1.1.1", "8.8.8.8"];
/// Bound on `wait_connected` — a SYN to a blackholed in-tunnel address must not
/// park the SOCKS handler thread forever. MUST stay well under OkHttp's 20 s
/// connect timeout together with the DNS budget (5 s + 12 s < 20 s), or every
/// slow in-tunnel resolve surfaces as a client timeout while we keep dialing.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(12);
/// Safety net for backpressured writers: the driver notifies on every drain, so
/// this should never fire — but a missed wake-up must error, not deadlock.
const WRITE_WAIT_TIMEOUT: Duration = Duration::from_secs(60);
/// smoltcp socket liveness: probes after 30 s idle, abort after 75 s silent —
/// a dead peer/session errors the connection instead of retransmitting forever.
const TCP_KEEPALIVE: Duration = Duration::from_secs(30);
const TCP_TIMEOUT: Duration = Duration::from_secs(75);
/// Max idle sleep when smoltcp reports NO pending deadline — WG timers (keepalive,
/// rekey) and health refresh still need a few wake-ups per second.
const IDLE_WAIT_CAP: Duration = Duration::from_millis(250);

/// Parsed, validated WireGuard parameters (from CoreConfig::WgThenSocks).
#[derive(Clone)]
pub struct WgParams {
    pub private_key: StaticSecret,
    pub peer_public_key: PublicKey,
    pub preshared_key: Option<[u8; 32]>,
    pub endpoint: SocketAddr,
    /// The tunnel-interior interface address(es), e.g. 10.0.0.2/32.
    pub interface_addrs: Vec<IpCidr>,
    /// WG-configured DNS servers (wg-quick `[Interface] DNS`); resolved over the
    /// tunnel. Empty → fall back to the phone's local resolver (with a DNS leak).
    pub dns_servers: Vec<IpAddress>,
    pub keepalive_secs: Option<u16>,
    /// Tunnel-interior MTU; smoltcp derives the TCP MSS from it.
    pub mtu: usize,
}

impl WgParams {
    /// Decode from the wire config. Keys are standard WireGuard base64 (32 bytes).
    pub fn parse(
        private_key_b64: &str,
        peer_public_key_b64: &str,
        preshared_key_b64: Option<&str>,
        endpoint: &str,
        interface_addrs: &[String],
        dns: &[String],
        keepalive_secs: u16,
        mtu: u16,
    ) -> Result<WgParams, String> {
        let sk = decode_key(private_key_b64).map_err(|e| format!("private_key: {e}"))?;
        let pk = decode_key(peer_public_key_b64).map_err(|e| format!("peer_public_key: {e}"))?;
        let psk = match preshared_key_b64 {
            Some(s) if !s.is_empty() => Some(decode_key(s).map_err(|e| format!("preshared_key: {e}"))?),
            _ => None,
        };
        let endpoint: SocketAddr = endpoint
            .parse()
            .map_err(|_| format!("endpoint '{endpoint}' is not host:port (resolve DNS before start)"))?;
        let addrs = interface_addrs
            .iter()
            .map(|s| parse_cidr(s))
            .collect::<Result<Vec<_>, _>>()?;
        if addrs.is_empty() {
            return Err("interface address required (e.g. 10.0.0.2/32)".into());
        }
        let dns_servers = dns
            .iter()
            .filter(|s| !s.trim().is_empty())
            .map(|s| {
                s.trim()
                    .parse::<IpAddr>()
                    .map(IpAddress::from)
                    .map_err(|_| format!("bad DNS server '{s}'"))
            })
            .collect::<Result<Vec<_>, _>>()?;
        Ok(WgParams {
            private_key: StaticSecret::from(sk),
            peer_public_key: PublicKey::from(pk),
            preshared_key: psk,
            endpoint,
            interface_addrs: addrs,
            dns_servers,
            keepalive_secs: if keepalive_secs == 0 { None } else { Some(keepalive_secs) },
            mtu: if mtu == 0 { DEFAULT_MTU } else { (mtu as usize).clamp(576, 1500) },
        })
    }
}

/// Standard x25519 clamping (RFC 7748), the same bit-fixing `wg genkey` applies.
fn clamp(mut k: [u8; 32]) -> [u8; 32] {
    k[0] &= 248;
    k[31] &= 127;
    k[31] |= 64;
    k
}

/// Generate a WireGuard keypair (base64), equivalent to `wg genkey`/`wg pubkey`.
/// The private key is CLAMPED to match `wg genkey` output exactly — x25519-dalek's
/// `to_bytes()` returns the raw random bytes (clamping is applied only at DH time),
/// which would otherwise export a non-standard, unclamped private key.
pub fn generate_keypair() -> crate::WireguardKeypair {
    let sk = StaticSecret::random_from_rng(rand::rngs::OsRng);
    let pk = PublicKey::from(&sk);
    let enc = base64::engine::general_purpose::STANDARD;
    crate::WireguardKeypair {
        private_key: enc.encode(clamp(sk.to_bytes())),
        public_key: enc.encode(pk.to_bytes()),
    }
}

/// Derive the base64 public key for a base64 private key (= `wg pubkey`), so the
/// user can verify the public key they gave the server matches this private key.
pub fn derive_public_key(private_key_b64: &str) -> Result<String, String> {
    let sk_bytes = decode_key(private_key_b64)?;
    let sk = StaticSecret::from(sk_bytes);
    let pk = PublicKey::from(&sk);
    Ok(base64::engine::general_purpose::STANDARD.encode(pk.to_bytes()))
}

fn decode_key(b64: &str) -> Result<[u8; 32], String> {
    let raw = base64::engine::general_purpose::STANDARD
        .decode(b64.trim())
        .map_err(|e| e.to_string())?;
    let arr: [u8; 32] = raw
        .as_slice()
        .try_into()
        .map_err(|_| format!("expected 32 bytes, got {}", raw.len()))?;
    Ok(arr)
}

fn parse_cidr(s: &str) -> Result<IpCidr, String> {
    let (ip, prefix) = match s.split_once('/') {
        Some((ip, p)) => (ip, p.parse::<u8>().map_err(|_| format!("bad prefix in {s}"))?),
        None => (s, 32),
    };
    let addr: IpAddr = ip.parse().map_err(|_| format!("bad ip in {s}"))?;
    Ok(match addr {
        IpAddr::V4(v4) => IpCidr::new(IpAddress::from(v4), prefix),
        IpAddr::V6(v6) => IpCidr::new(IpAddress::from(v6), if s.contains('/') { prefix } else { 128 }),
    })
}

// ── The netstack device: bridges smoltcp IP packets to/from boringtun ────────

/// A smoltcp [`Device`] whose "wire" is two in-memory packet queues. The driver
/// drains `outbound` (smoltcp→WG, to be encrypted) and fills `inbound` (WG→smoltcp,
/// freshly decrypted) between polls.
struct TunDevice {
    inbound: VecDeque<Vec<u8>>,
    outbound: VecDeque<Vec<u8>>,
    mtu: usize,
}

impl TunDevice {
    fn new(mtu: usize) -> Self {
        TunDevice { inbound: VecDeque::new(), outbound: VecDeque::new(), mtu }
    }
}

struct RxToken(Vec<u8>);
struct TxToken<'a>(&'a mut VecDeque<Vec<u8>>);

impl smoltcp::phy::RxToken for RxToken {
    fn consume<R, F: FnOnce(&[u8]) -> R>(self, f: F) -> R {
        f(&self.0)
    }
}

impl smoltcp::phy::TxToken for TxToken<'_> {
    fn consume<R, F: FnOnce(&mut [u8]) -> R>(self, len: usize, f: F) -> R {
        let mut buf = vec![0u8; len];
        let r = f(&mut buf);
        self.0.push_back(buf);
        r
    }
}

impl Device for TunDevice {
    type RxToken<'a> = RxToken;
    type TxToken<'a> = TxToken<'a>;

    fn receive(&mut self, _t: SmolInstant) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        let pkt = self.inbound.pop_front()?;
        Some((RxToken(pkt), TxToken(&mut self.outbound)))
    }

    fn transmit(&mut self, _t: SmolInstant) -> Option<Self::TxToken<'_>> {
        Some(TxToken(&mut self.outbound))
    }

    fn capabilities(&self) -> DeviceCapabilities {
        let mut caps = DeviceCapabilities::default();
        caps.medium = Medium::Ip;
        caps.max_transmission_unit = self.mtu;
        caps
    }
}

// ── Per-connection shared state (driver ↔ application thread) ────────────────
//
// DESIGN RULE (hard-won): these flags are NOT a state machine. They are
// stateless PROJECTIONS of the smoltcp socket state, recomputed from scratch by
// the driver on every tick (`Driver::publish_conn`) — never "transitioned" by
// hand at event edges. Edge-triggered transitions are where the F1 deadlock and
// the SynSent misjudgment lived: an enum with N states has N² edges to remember;
// a recomputed projection has zero.

struct ConnShared {
    handle: SocketHandle,
    inner: Mutex<ConnInner>,
    cv: Condvar,
}

struct ConnInner {
    /// Projection: the socket reached Established (smoltcp may_send).
    connected: bool,
    /// Projection: the socket is in a terminal state (Closed/TimeWait) or the
    /// driver is exiting. Set-and-never-cleared by recomputation; terminal by
    /// definition, so no resurrection edge exists.
    failed: bool,
    /// app → socket (driver drains into smoltcp send buffer)
    to_socket: VecDeque<u8>,
    /// socket → app (driver fills from smoltcp recv buffer)
    from_socket: VecDeque<u8>,
    /// app closed its write half; driver should close the socket once drained.
    app_write_closed: bool,
    /// socket recv finished (peer FIN) — app read returns EOF once drained.
    recv_finished: bool,
}

impl ConnShared {
    fn new(handle: SocketHandle) -> Arc<Self> {
        Arc::new(ConnShared {
            handle,
            inner: Mutex::new(ConnInner {
                connected: false,
                failed: false,
                to_socket: VecDeque::new(),
                from_socket: VecDeque::new(),
                app_write_closed: false,
                recv_finished: false,
            }),
            cv: Condvar::new(),
        })
    }
}

// ── The driver's single mailbox (actor model) ────────────────────────────────
//
// The driver thread blocks on ONE primitive — this channel — matching smoltcp's
// cooperative scheduling: it sleeps until an event arrives or the smoltcp-computed
// deadline (poll_delay) expires. Producers: app threads (Dial/Resolve/Shutdown),
// the dedicated UDP reader thread (Datagram), and TunnelStream writes (Kick).
// No fixed poll tick, no signal bytes smuggled through the network socket.

enum Event {
    Dial {
        addr: IpEndpoint,
        reply: Sender<io::Result<Arc<ConnShared>>>,
    },
    /// Resolve a hostname over the tunnel using the WG-configured DNS servers.
    Resolve {
        host: String,
        reply: Sender<io::Result<IpAddr>>,
    },
    /// A WireGuard datagram from the peer (the connected socket guarantees the
    /// source — the kernel does the filtering, not us).
    Datagram(Vec<u8>),
    /// Pure wake-up: app bytes were queued / a write half was closed. Carries no
    /// data — the driver re-derives everything from the authoritative state.
    Kick,
    Shutdown,
}

/// Handle to the running tunnel. Shared via `Arc`; call [`WgTunnel::shutdown`] (or
/// drop the last reference) to stop the driver.
pub struct WgTunnel {
    events: Sender<Event>,
    /// Effective DNS servers: the WG-configured ones, or our defaults if none were
    /// given (design semantic). Used by both the UDP and TCP in-tunnel resolvers.
    dns_servers: Vec<IpAddr>,
    handshake_ok: Arc<AtomicBool>,
    last_handshake_epoch: Arc<AtomicI64>,
    tx_bytes: Arc<AtomicU64>,
    rx_bytes: Arc<AtomicU64>,
    stopped: Arc<AtomicBool>,
    /// Set when the driver thread has EXITED (any reason). health() surfaces this
    /// so Kotlin can declare Failed even if no handshake ever completed.
    driver_dead: Arc<AtomicBool>,
    /// Driver thread handle, joined by [`shutdown`] so teardown is deterministic even
    /// when an in-flight tunnel connection still holds an `Arc<WgTunnel>` (otherwise a
    /// leaked driver keeps racing the peer — two tunnels for one session).
    join: Mutex<Option<JoinHandle<()>>>,
    /// The UDP reader thread (feeds Event::Datagram into the mailbox).
    reader_join: Mutex<Option<JoinHandle<()>>>,
}

impl WgTunnel {
    /// Build the tunnel and spawn its driver thread. Returns once the netstack is
    /// up; the WireGuard handshake completes asynchronously on first traffic.
    pub fn start(mut params: WgParams) -> io::Result<WgTunnel> {
        // CONNECTED socket: the kernel accepts only datagrams from the WG peer
        // (source filtering is not our job). Shared between the reader thread
        // (recv) and the driver (send).
        let udp = Arc::new(UdpSocket::bind(("0.0.0.0", 0))?);
        udp.connect(params.endpoint)?;

        // Design semantic: use the configured [Interface] DNS if given, else our
        // defaults. Both the UDP (smoltcp) and TCP resolvers query these.
        if params.dns_servers.is_empty() {
            params.dns_servers = DEFAULT_DNS
                .iter()
                .map(|s| IpAddress::from(s.parse::<std::net::Ipv4Addr>().unwrap()))
                .collect();
        }
        let dns_servers: Vec<IpAddr> = params.dns_servers.iter().map(|a| IpAddr::from(*a)).collect();

        let (events_tx, events_rx) = mpsc::channel::<Event>();
        let handshake_ok = Arc::new(AtomicBool::new(false));
        let last_handshake_epoch = Arc::new(AtomicI64::new(0));
        let tx_bytes = Arc::new(AtomicU64::new(0));
        let rx_bytes = Arc::new(AtomicU64::new(0));
        let stopped = Arc::new(AtomicBool::new(false));
        let driver_dead = Arc::new(AtomicBool::new(false));

        // Dedicated reader: the driver's only blocking primitive is the mailbox,
        // so peer datagrams are forwarded into it as plain events. The connected
        // socket means the kernel filters non-peer sources for us.
        let reader = {
            let udp = Arc::clone(&udp);
            let events = events_tx.clone();
            let stopped = Arc::clone(&stopped);
            std::thread::Builder::new()
                .name("gallery-wg-reader".into())
                .spawn(move || read_datagrams(udp, events, stopped))
                .map_err(|e| io::Error::new(io::ErrorKind::Other, e.to_string()))?
        };

        let driver = Driver {
            events: events_rx,
            handshake_ok: Arc::clone(&handshake_ok),
            last_handshake_epoch: Arc::clone(&last_handshake_epoch),
            tx_bytes: Arc::clone(&tx_bytes),
            rx_bytes: Arc::clone(&rx_bytes),
            stopped: Arc::clone(&stopped),
        };
        // Panic-safety: if the driver ever unwinds (or exits), zero the health atoms
        // so `health()` reports the tunnel DOWN instead of freezing at its last-good
        // values — otherwise diag lies "OK" while all traffic is dead. driver_dead
        // lets the Kotlin monitor declare Failed even before any handshake.
        // (Requires panic=unwind — do NOT set panic="abort" in Cargo.toml.)
        let hs_ok = Arc::clone(&handshake_ok);
        let hs_epoch = Arc::clone(&last_handshake_epoch);
        let dead = Arc::clone(&driver_dead);
        let handle = std::thread::Builder::new()
            .name("gallery-wg-driver".into())
            .spawn(move || {
                let _ = catch_unwind(AssertUnwindSafe(|| driver.run(params, udp)));
                hs_ok.store(false, Ordering::SeqCst);
                hs_epoch.store(0, Ordering::SeqCst);
                dead.store(true, Ordering::SeqCst);
            })
            .map_err(|e| io::Error::new(io::ErrorKind::Other, e.to_string()))?;

        Ok(WgTunnel {
            events: events_tx,
            dns_servers,
            handshake_ok,
            last_handshake_epoch,
            tx_bytes,
            rx_bytes,
            stopped,
            driver_dead,
            join: Mutex::new(Some(handle)),
            reader_join: Mutex::new(Some(reader)),
        })
    }

    /// Deterministically stop the driver and wait for its thread to exit. Safe to
    /// call more than once. Unlike relying on `Drop`, this does NOT depend on every
    /// `Arc<WgTunnel>` having been released — the driver wakes on the Shutdown
    /// event, so a stuck in-flight connection can't keep it alive.
    pub fn shutdown(&self) {
        self.stopped.store(true, Ordering::SeqCst);
        let _ = self.events.send(Event::Shutdown);
        if let Some(handle) = self.join.lock().unwrap().take() {
            let _ = handle.join();
        }
        if let Some(handle) = self.reader_join.lock().unwrap().take() {
            let _ = handle.join();
        }
    }

    /// Blocking dial of `host:port` (an IP literal inside the tunnel) — the SOCKS5
    /// upstream address. Returns a stream once the TCP connection is established.
    pub fn dial(&self, host: &str, port: u16) -> io::Result<TunnelStream> {
        let ip: IpAddr = host.parse().map_err(|_| {
            io::Error::new(
                io::ErrorKind::InvalidInput,
                format!("upstream SOCKS host '{host}' must be an in-tunnel IP literal"),
            )
        })?;
        let addr = IpEndpoint::new(IpAddress::from(ip), port);
        let (tx, rx) = mpsc::channel();
        self.events
            .send(Event::Dial { addr, reply: tx })
            .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "tunnel driver stopped"))?;
        let shared = rx
            .recv()
            .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "tunnel driver stopped"))??;

        let stream = TunnelStream {
            shared,
            events: self.events.clone(),
        };
        stream.wait_connected()?;
        Ok(stream)
    }

    /// The effective DNS servers (configured, or defaults) used for resolution.
    pub fn dns_servers(&self) -> &[IpAddr] {
        &self.dns_servers
    }

    /// Resolve `host` to an IP using the effective DNS servers, over UDP (smoltcp).
    pub fn resolve(&self, host: &str) -> io::Result<IpAddr> {
        let (tx, rx) = mpsc::channel();
        self.events
            .send(Event::Resolve { host: host.to_string(), reply: tx })
            .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "tunnel driver stopped"))?;
        rx.recv_timeout(Duration::from_secs(6))
            .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "in-tunnel UDP DNS timed out"))?
    }

    /// UDP in-tunnel resolve, returning None on failure — so the caller can race it
    /// against the TCP path and fall back to a local lookup.
    pub fn resolve_or(&self, host: &str) -> Option<IpAddr> {
        if !self.dns_servers.is_empty() {
            self.resolve(host).ok()
        } else {
            None
        }
    }

    pub fn handshake_ok(&self) -> bool {
        self.handshake_ok.load(Ordering::SeqCst)
    }

    /// True once the driver thread has exited (shutdown OR unexpected death).
    pub fn driver_dead(&self) -> bool {
        self.driver_dead.load(Ordering::SeqCst)
    }

    /// WireGuard data bytes sent / received through the tunnel (boringtun stats).
    pub fn tx_bytes(&self) -> u64 {
        self.tx_bytes.load(Ordering::SeqCst)
    }

    pub fn rx_bytes(&self) -> u64 {
        self.rx_bytes.load(Ordering::SeqCst)
    }

    pub fn last_handshake_epoch(&self) -> Option<i64> {
        let v = self.last_handshake_epoch.load(Ordering::SeqCst);
        if v == 0 {
            None
        } else {
            Some(v)
        }
    }
}

impl Drop for WgTunnel {
    fn drop(&mut self) {
        // Idempotent with an explicit shutdown(); joins the driver if still running.
        self.shutdown();
    }
}

/// smoltcp IP address → std IP address (they share `core::net` underneath).
fn ip_to_std(addr: IpAddress) -> IpAddr {
    IpAddr::from(addr)
}

/// Human-readable destination address of a raw IPv4/IPv6 packet, for tracing.
fn ip_dst(pkt: &[u8]) -> String {
    match pkt.first().map(|b| b >> 4) {
        Some(4) if pkt.len() >= 20 => {
            std::net::Ipv4Addr::new(pkt[16], pkt[17], pkt[18], pkt[19]).to_string()
        }
        Some(6) if pkt.len() >= 40 => {
            let mut o = [0u8; 16];
            o.copy_from_slice(&pkt[24..40]);
            std::net::Ipv6Addr::from(o).to_string()
        }
        _ => "?".into(),
    }
}

/// Human-readable source address of a raw IPv4/IPv6 packet, for tracing.
fn ip_src(pkt: &[u8]) -> String {
    match pkt.first().map(|b| b >> 4) {
        Some(4) if pkt.len() >= 20 => {
            std::net::Ipv4Addr::new(pkt[12], pkt[13], pkt[14], pkt[15]).to_string()
        }
        Some(6) if pkt.len() >= 40 => {
            let mut o = [0u8; 16];
            o.copy_from_slice(&pkt[8..24]);
            std::net::Ipv6Addr::from(o).to_string()
        }
        _ => "?".into(),
    }
}

/// Resolve `host` to an IP via DNS-over-TCP THROUGH the tunnel (public resolvers).
/// UDP DNS can't survive a transparent-SOCKS exit, so WgOnly resolves over TCP —
/// the query connection itself is tproxied to the upstream SOCKS and resolved
/// remotely. Blocking; the caller wraps it in a timeout.
pub fn tcp_dns_resolve(tunnel: &WgTunnel, host: &str) -> io::Result<IpAddr> {
    let mut last = io::Error::new(io::ErrorKind::NotFound, "no resolver reachable");
    for resolver in tunnel.dns_servers() {
        match tunnel.dial(&resolver.to_string(), 53) {
            Ok(mut stream) => match tcp_dns_query(&mut stream, host) {
                Ok(ip) => {
                    log::debug!("wg: tcp-dns {host} -> {ip} via {resolver}");
                    return Ok(ip);
                }
                Err(e) => last = e,
            },
            Err(e) => last = e,
        }
    }
    Err(last)
}

/// One DNS A query/response exchange over a connected TCP stream (RFC 1035 §4.2.2).
fn tcp_dns_query<S: Read + Write>(stream: &mut S, host: &str) -> io::Result<IpAddr> {
    let query = build_a_query(host);
    stream.write_all(&(query.len() as u16).to_be_bytes())?;
    stream.write_all(&query)?;
    stream.flush()?;

    let mut len = [0u8; 2];
    stream.read_exact(&mut len)?;
    let rlen = u16::from_be_bytes(len) as usize;
    if rlen == 0 || rlen > 8192 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "bad DNS length"));
    }
    let mut resp = vec![0u8; rlen];
    stream.read_exact(&mut resp)?;
    parse_a_record(&resp).ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "no A record"))
}

fn build_a_query(host: &str) -> Vec<u8> {
    let mut q = Vec::with_capacity(host.len() + 18);
    q.extend_from_slice(&[0x12, 0x34]); // id
    q.extend_from_slice(&[0x01, 0x00]); // flags: recursion desired
    q.extend_from_slice(&[0x00, 0x01]); // QDCOUNT
    q.extend_from_slice(&[0, 0, 0, 0, 0, 0]); // AN/NS/AR
    for label in host.trim_matches('.').split('.') {
        q.push(label.len() as u8);
        q.extend_from_slice(label.as_bytes());
    }
    q.push(0);
    q.extend_from_slice(&[0x00, 0x01]); // QTYPE A
    q.extend_from_slice(&[0x00, 0x01]); // QCLASS IN
    q
}

fn parse_a_record(msg: &[u8]) -> Option<IpAddr> {
    if msg.len() < 12 || msg[3] & 0x0F != 0 {
        return None; // too short, or RCODE != 0
    }
    let qd = u16::from_be_bytes([msg[4], msg[5]]) as usize;
    let an = u16::from_be_bytes([msg[6], msg[7]]) as usize;
    let mut off = 12;
    for _ in 0..qd {
        off = dns_skip_name(msg, off)? + 4;
    }
    for _ in 0..an {
        off = dns_skip_name(msg, off)?;
        if off + 10 > msg.len() {
            return None;
        }
        let typ = u16::from_be_bytes([msg[off], msg[off + 1]]);
        let rdlen = u16::from_be_bytes([msg[off + 8], msg[off + 9]]) as usize;
        let rd = off + 10;
        if typ == 1 && rdlen == 4 && rd + 4 <= msg.len() {
            return Some(IpAddr::V4(std::net::Ipv4Addr::new(msg[rd], msg[rd + 1], msg[rd + 2], msg[rd + 3])));
        }
        off = rd + rdlen;
    }
    None
}

fn dns_skip_name(msg: &[u8], start: usize) -> Option<usize> {
    let mut off = start;
    loop {
        let len = *msg.get(off)? as usize;
        match len {
            0 => return Some(off + 1),
            _ if len & 0xC0 == 0xC0 => return Some(off + 2), // compression pointer
            _ => off += 1 + len,
        }
    }
}

/// Ask boringtun to (re)start the WireGuard handshake and send the initiation.
/// `force_resend=false` makes it a no-op while an attempt is already in flight,
/// so this is safe to call on a timer.
fn initiate_handshake(tunn: &mut Tunn, udp: &UdpSocket, scratch: &mut [u8]) {
    match tunn.format_handshake_initiation(scratch, false) {
        TunnResult::WriteToNetwork(packet) => {
            let _ = udp.send(packet);
            log::debug!("wg: sent handshake initiation ({} bytes)", packet.len());
        }
        TunnResult::Done => {}
        TunnResult::Err(e) => log::warn!("wg: handshake initiation error: {e:?}"),
        _ => {}
    }
}

/// Recompute the published views from the AUTHORITATIVE smoltcp socket state.
/// This is the ONLY place the driver ever sets `connected`/`failed` — a pure
/// projection with no transition rules to get wrong:
///
///   connected = may_send()  (Established | CloseWait — SynSent is simply false)
///   failed    = state ∈ {Closed, TimeWait}  (RST / timeout / abort, mid- or
///              post-connect alike — Closed has no outgoing edges, so `failed`
///              is sticky by absorption, not by a remembered transition)
///
/// Returns true when a published value changed (→ wake the waiters).
fn publish_conn(sock: &tcp::Socket, g: &mut ConnInner) -> bool {
    let mut changed = false;
    let connected = sock.may_send();
    if g.connected != connected {
        g.connected = connected;
        changed = true;
    }
    let terminal = matches!(sock.state(), tcp::State::Closed | tcp::State::TimeWait);
    if terminal && !g.failed {
        g.failed = true;
        g.recv_finished = true; // readers drain buffered bytes, then see EOF
        changed = true;
    }
    changed
}

// ── The application-facing blocking stream ───────────────────────────────────

/// A blocking, TcpStream-like handle to one tunnelled TCP connection.
pub struct TunnelStream {
    shared: Arc<ConnShared>,
    events: Sender<Event>,
}

impl TunnelStream {
    fn wait_connected(&self) -> io::Result<()> {
        let deadline = StdInstant::now() + CONNECT_TIMEOUT;
        let mut g = self.shared.inner.lock().unwrap();
        loop {
            if g.connected {
                return Ok(());
            }
            if g.failed {
                return Err(io::Error::new(
                    io::ErrorKind::ConnectionRefused,
                    "tunnel TCP connect failed",
                ));
            }
            let remain = deadline.saturating_duration_since(StdInstant::now());
            if remain.is_zero() {
                // Blackholed target (peer not routing, dead exit): fail the
                // conn so the reaper can collect it, don't park forever.
                g.failed = true;
                g.recv_finished = true;
                self.shared.cv.notify_all();
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    "tunnel TCP connect timed out",
                ));
            }
            let (guard, _) = self
                .shared
                .cv
                .wait_timeout(g, remain.min(Duration::from_secs(1)))
                .unwrap();
            g = guard;
        }
    }
}

impl Read for TunnelStream {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let mut g = self.shared.inner.lock().unwrap();
        loop {
            if !g.from_socket.is_empty() {
                let n = g.from_socket.len().min(buf.len());
                let (a, b) = g.from_socket.as_slices();
                let na = a.len().min(n);
                buf[..na].copy_from_slice(&a[..na]);
                if n > na {
                    buf[na..n].copy_from_slice(&b[..n - na]);
                }
                g.from_socket.drain(..n);
                return Ok(n);
            }
            if g.recv_finished || g.failed {
                return Ok(0); // EOF
            }
            g = self.shared.cv.wait(g).unwrap();
        }
    }
}

impl Write for TunnelStream {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let mut g = self.shared.inner.lock().unwrap();
        loop {
            if g.failed {
                return Err(io::Error::new(io::ErrorKind::BrokenPipe, "tunnel connection failed"));
            }
            if g.to_socket.len() < CONN_BUF_CAP {
                g.to_socket.extend(buf.iter().copied());
                self.shared.cv.notify_all();
                drop(g);
                let _ = self.events.send(Event::Kick);
                return Ok(buf.len());
            }
            // Backpressure: wait for the driver to drain into the socket. Bounded —
            // the driver notifies on every drain, so a timeout here means the driver
            // is gone; error instead of deadlocking the splice thread.
            let (guard, elapsed) = self.shared.cv.wait_timeout(g, WRITE_WAIT_TIMEOUT).unwrap();
            g = guard;
            if elapsed.timed_out() && g.to_socket.len() >= CONN_BUF_CAP {
                g.failed = true;
                g.recv_finished = true;
                self.shared.cv.notify_all();
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    "tunnel write stalled (driver not draining)",
                ));
            }
        }
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

/// Dropping the last app handle closes the write half (graceful FIN once drained),
/// so paths that drop a stream without an explicit `shutdown_write` (DNS queries,
/// failed upstream handshakes) don't leak the smoltcp socket forever.
impl Drop for TunnelStream {
    fn drop(&mut self) {
        if Arc::strong_count(&self.shared) <= 2 {
            // Only this handle + the driver's conns entry remain.
            let mut g = self.shared.inner.lock().unwrap();
            g.app_write_closed = true;
            self.shared.cv.notify_all();
            drop(g);
            let _ = self.events.send(Event::Kick);
        }
    }
}

impl Clone for TunnelStream {
    fn clone(&self) -> Self {
        TunnelStream {
            shared: Arc::clone(&self.shared),
            events: self.events.clone(),
        }
    }
}

impl Conn for TunnelStream {
    fn try_clone_box(&self) -> io::Result<Box<dyn Conn>> {
        Ok(Box::new(self.clone()))
    }
    fn shutdown_write(&self) {
        let mut g = self.shared.inner.lock().unwrap();
        g.app_write_closed = true;
        self.shared.cv.notify_all();
        drop(g);
        let _ = self.events.send(Event::Kick);
    }
}

/// The dedicated UDP reader: forwards peer datagrams into the driver's mailbox.
/// The socket is connected, so every recv is guaranteed to be from the WG peer.
/// Its 250 ms timeout only paces the `stopped` check — it's the ONE idle waker,
/// letting the driver itself sleep fully event-driven.
fn read_datagrams(udp: Arc<UdpSocket>, events: Sender<Event>, stopped: Arc<AtomicBool>) {
    udp.set_read_timeout(Some(IDLE_WAIT_CAP)).ok();
    let mut buf = [0u8; SCRATCH];
    while !stopped.load(Ordering::SeqCst) {
        match udp.recv(&mut buf) {
            Ok(n) => {
                if events.send(Event::Datagram(buf[..n].to_vec())).is_err() {
                    break; // driver gone
                }
            }
            Err(ref e)
                if matches!(
                    e.kind(),
                    io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut | io::ErrorKind::Interrupted
                ) => {}
            Err(e) => {
                log::warn!("wg: udp recv error: {e}");
                break;
            }
        }
    }
}

// ── The single-threaded driver ───────────────────────────────────────────────

struct Driver {
    events: Receiver<Event>,
    handshake_ok: Arc<AtomicBool>,
    last_handshake_epoch: Arc<AtomicI64>,
    tx_bytes: Arc<AtomicU64>,
    rx_bytes: Arc<AtomicU64>,
    stopped: Arc<AtomicBool>,
}

impl Driver {
    fn run(self, params: WgParams, udp: Arc<UdpSocket>) {
        let mut tunn = Tunn::new(
            params.private_key.clone(),
            params.peer_public_key,
            params.preshared_key,
            params.keepalive_secs,
            0,
            None,
        );

        let mut device = TunDevice::new(params.mtu);
        let smol_start = StdInstant::now();
        let mut config = Config::new(HardwareAddress::Ip);
        // Randomize TCP ISN/port selection per boot (smoltcp guidance).
        config.random_seed = rand::random();
        let mut iface = Interface::new(config, &mut device, SmolInstant::from_millis(0));
        iface.update_ip_addrs(|addrs| {
            for cidr in &params.interface_addrs {
                let _ = addrs.push(*cidr);
            }
        });
        // Point-to-point tunnel: a default route makes every non-local dest go out
        // the single device; boringtun encapsulates it to the one WG peer.
        if let Some(v4) = params.interface_addrs.iter().find_map(|c| match c.address() {
            IpAddress::Ipv4(a) => Some(a),
            _ => None,
        }) {
            let _ = iface.routes_mut().add_default_ipv4_route(v4);
        }
        if let Some(v6) = params.interface_addrs.iter().find_map(|c| match c.address() {
            IpAddress::Ipv6(a) => Some(a),
            _ => None,
        }) {
            let _ = iface.routes_mut().add_default_ipv6_route(v6);
        }

        let mut sockets = SocketSet::new(Vec::new());
        let mut conns: Vec<Arc<ConnShared>> = Vec::new();
        let mut next_local_port: u16 = 49152;
        let mut scratch = [0u8; SCRATCH];

        // In-tunnel DNS: a smoltcp DNS socket querying the WG-configured servers.
        let dns_handle: Option<SocketHandle> = if params.dns_servers.is_empty() {
            None
        } else {
            let queries: Vec<Option<dns::DnsQuery>> = (0..4).map(|_| None).collect();
            let sock = dns::Socket::new(&params.dns_servers, queries);
            Some(sockets.add(sock))
        };
        // Pending resolutions awaiting a smoltcp DNS answer.
        let mut dns_pending: Vec<(dns::QueryHandle, StdInstant, Sender<io::Result<IpAddr>>)> = Vec::new();

        // WireGuard is lazy: without traffic it never initiates the first handshake,
        // so the peer would never see us. Proactively initiate now (and re-initiate
        // below until established), like a normal client with persistent keepalive.
        // Log the exact source identity — a mismatch with the server's AllowedIPs
        // for this peer is the #1 reason data is dropped after a good handshake.
        let ifaddrs: Vec<String> = params.interface_addrs.iter().map(|c| c.to_string()).collect();
        let dnss: Vec<String> = params.dns_servers.iter().map(|s| s.to_string()).collect();
        log::info!("wg: interface={ifaddrs:?} dns={dnss:?} (egress source = interface address)");
        log::info!("wg: driver up, initiating handshake");
        initiate_handshake(&mut tunn, &udp, &mut scratch);
        let mut last_hs_attempt = StdInstant::now();
        let mut last_timers = StdInstant::now();
        let mut established = false;

        'drive: loop {
            if self.stopped.load(Ordering::SeqCst) {
                break;
            }

            // 1. Drain the mailbox (non-blocking): apply every pending event.
            while let Ok(event) = self.events.try_recv() {
                match event {
                    Event::Shutdown => break 'drive,
                    Event::Kick => {} // wake-only; state is re-derived below
                    Event::Datagram(data) => {
                        log::debug!("wg: <- udp {} B from peer", data.len());
                        self.handle_incoming(&mut tunn, &udp, &data, &mut device, &mut scratch);
                    }
                    Event::Dial { addr, reply } => {
                        self.exec_dial(addr, reply, &mut sockets, &mut iface, &mut conns, &mut next_local_port);
                    }
                    Event::Resolve { host, reply } => {
                        self.exec_resolve(host, reply, &mut sockets, &mut iface, dns_handle, &mut dns_pending);
                    }
                }
            }

            // 2. App → socket: move queued app bytes into smoltcp send buffers,
            //    and close sockets whose app write half is done. CRITICAL: notify
            //    after draining — a backpressured writer parks on the condvar and
            //    inbound events (step 4) are the only other wake source, but during
            //    an upload the server sends nothing until the body completes, so
            //    without this notify the writer sleeps forever at ~one buffer.
            for shared in &conns {
                let sock = sockets.get_mut::<tcp::Socket>(shared.handle);
                let mut g = shared.inner.lock().unwrap();
                if g.failed || sock.state() == tcp::State::Closed {
                    // Undeliverable bytes would block the reaper forever — drop them.
                    if !g.to_socket.is_empty() {
                        g.to_socket.clear();
                        shared.cv.notify_all();
                    }
                    continue;
                }
                let mut drained = false;
                while sock.can_send() && !g.to_socket.is_empty() {
                    // Copy out only the contiguous head slice (non-empty for a
                    // non-empty deque) — no whole-queue copy per tick.
                    let n = {
                        let (a, _) = g.to_socket.as_slices();
                        match sock.send_slice(a) {
                            Ok(0) => break,
                            Ok(n) => n,
                            Err(_) => break,
                        }
                    };
                    g.to_socket.drain(..n);
                    drained = true;
                }
                if drained {
                    shared.cv.notify_all();
                }
                if g.app_write_closed && g.to_socket.is_empty() && sock.may_send() {
                    sock.close();
                }
            }

            // 3. Poll the netstack (drives TCP state machines; fills device.outbound).
            let now = SmolInstant::from_micros(smol_start.elapsed().as_micros() as i64);
            iface.poll(now, &mut device, &mut sockets);

            // 3b. Complete in-tunnel DNS queries (or time them out).
            if let Some(h) = dns_handle {
                let sock = sockets.get_mut::<dns::Socket>(h);
                dns_pending.retain(|(q, started, reply)| match sock.get_query_result(*q) {
                    Ok(addrs) => {
                        let result = addrs
                            .first()
                            .map(|a| ip_to_std(*a))
                            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "no DNS answer"));
                        let _ = reply.send(result);
                        false
                    }
                    Err(dns::GetQueryResultError::Pending) => {
                        if started.elapsed() >= Duration::from_secs(5) {
                            let _ = reply.send(Err(io::Error::new(
                                io::ErrorKind::TimedOut,
                                "in-tunnel DNS timed out",
                            )));
                            false
                        } else {
                            true
                        }
                    }
                    Err(_) => {
                        let _ = reply.send(Err(io::Error::new(
                            io::ErrorKind::Other,
                            "in-tunnel DNS query failed",
                        )));
                        false
                    }
                });
            }

            // 4. Socket → app: pull decoded bytes out to app read buffers; then
            //    republish the derived views from the AUTHORITATIVE smoltcp state.
            for shared in &conns {
                let sock = sockets.get_mut::<tcp::Socket>(shared.handle);
                let mut g = shared.inner.lock().unwrap();
                let mut changed = false;
                while sock.can_recv() && g.from_socket.len() < CONN_BUF_CAP {
                    let mut tmp = [0u8; 4096];
                    match sock.recv_slice(&mut tmp) {
                        Ok(0) => break,
                        Ok(n) => {
                            g.from_socket.extend(&tmp[..n]);
                            changed = true;
                        }
                        Err(_) => break,
                    }
                }
                if !sock.may_recv() && sock.state() != tcp::State::SynSent && !g.recv_finished {
                    // Peer FIN or connection gone: reads drain, then EOF.
                    g.recv_finished = true;
                    changed = true;
                }
                if publish_conn(sock, &mut g) {
                    changed = true;
                }
                if changed {
                    shared.cv.notify_all();
                }
            }

            // 5. Encapsulate everything smoltcp emitted and send over WG/UDP.
            while let Some(pkt) = device.outbound.pop_front() {
                let (src, dst, len) = (ip_src(&pkt), ip_dst(&pkt), pkt.len());
                match tunn.encapsulate(&pkt, &mut scratch) {
                    TunnResult::WriteToNetwork(out) => {
                        let n = out.len();
                        let _ = udp.send(out);
                        log::debug!("wg: egress {src} -> {dst} ({len} B) -> SENT {n} B on wire");
                    }
                    // Queued: no ready session, so this data is NOT on the wire. If
                    // this persists the peer never confirms the session (→ re-handshake loop).
                    TunnResult::Done => log::debug!("wg: egress {src} -> {dst} ({len} B) -> QUEUED (no ready session)"),
                    TunnResult::Err(e) => log::warn!("wg: egress {src} -> {dst} encapsulate error: {e:?}"),
                    _ => {}
                }
            }

            // 6. EVENT-DRIVEN WAIT (smoltcp's cooperative scheduling model): block
            //    on the mailbox until an event arrives or the smoltcp-computed
            //    deadline (poll_delay: retransmit/keepalive/timeout timers)
            //    expires. No fixed poll tick — idle costs ~4 wake-ups/sec (the WG
            //    timers below), not 200; app events wake instantly via Kick.
            let busy = !dns_pending.is_empty()
                || conns.iter().any(|c| !c.inner.lock().unwrap().to_socket.is_empty());
            let wait = if busy {
                Duration::from_millis(1)
            } else {
                let now = SmolInstant::from_micros(smol_start.elapsed().as_micros() as i64);
                iface
                    .poll_delay(now, &sockets)
                    .map(|d| Duration::from_micros(d.total_micros()))
                    .unwrap_or(IDLE_WAIT_CAP)
                    .clamp(Duration::from_millis(1), IDLE_WAIT_CAP)
            };
            match self.events.recv_timeout(wait) {
                Ok(Event::Shutdown) => break 'drive,
                Ok(Event::Kick) => {}
                Ok(Event::Datagram(data)) => {
                    log::debug!("wg: <- udp {} B from peer", data.len());
                    self.handle_incoming(&mut tunn, &udp, &data, &mut device, &mut scratch);
                }
                Ok(Event::Dial { addr, reply }) => {
                    self.exec_dial(addr, reply, &mut sockets, &mut iface, &mut conns, &mut next_local_port);
                }
                Ok(Event::Resolve { host, reply }) => {
                    self.exec_resolve(host, reply, &mut sockets, &mut iface, dns_handle, &mut dns_pending);
                }
                Err(mpsc::RecvTimeoutError::Timeout) => {}
                Err(mpsc::RecvTimeoutError::Disconnected) => break 'drive,
            }

            // 7. WireGuard timers (handshake init, keepalive, rekey). boringtun's
            //    timers are second-granular; throttle to ~250 ms (it also resets
            //    the rate limiter on every call).
            if last_timers.elapsed() >= Duration::from_millis(250) {
                last_timers = StdInstant::now();
                match tunn.update_timers(&mut scratch) {
                    TunnResult::WriteToNetwork(out) => {
                        let _ = udp.send(out);
                    }
                    TunnResult::Err(e) => log::debug!("wg timer error: {e:?}"),
                    _ => {}
                }
            }

            // 8. Refresh handshake health + transfer counters from boringtun stats.
            let (since_handshake, tx, rx, ..) = tunn.stats();
            self.tx_bytes.store(tx as u64, Ordering::SeqCst);
            self.rx_bytes.store(rx as u64, Ordering::SeqCst);
            let ok = since_handshake.is_some();
            self.handshake_ok.store(ok, Ordering::SeqCst);
            // Throughput is exposed via health()/transport_info (tx/rx bytes) for the
            // in-app diagnostics screen — deliberately NOT logged, to keep logcat quiet.
            // Log establish/lose transitions and keep re-initiating until up.
            if ok && !established {
                established = true;
                log::info!("wg: handshake established");
            } else if !ok && established {
                established = false;
                // Report the tunnel as down (not a stale "recent handshake") so diag
                // and the Kotlin monitor can see the loss and trigger a reconnect.
                self.last_handshake_epoch.store(0, Ordering::SeqCst);
                log::warn!("wg: handshake lost, re-initiating");
            }
            if !ok && last_hs_attempt.elapsed() >= Duration::from_secs(1) {
                last_hs_attempt = StdInstant::now();
                // No-op while an attempt is already in flight; resends after expiry.
                initiate_handshake(&mut tunn, &udp, &mut scratch);
            }
            if ok {
                // Approximate wall-clock epoch of the last handshake.
                if let Ok(now_sys) = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH) {
                    let secs = now_sys.as_secs() as i64
                        - since_handshake.map(|d| d.as_secs() as i64).unwrap_or(0);
                    self.last_handshake_epoch.store(secs, Ordering::SeqCst);
                }
            }

            // 9. Reap fully-closed connections so long sessions don't accumulate
            //    smoltcp sockets (each holds 512 KiB of buffers).
            conns.retain(|shared| {
                let g = shared.inner.lock().unwrap();
                let drained = g.recv_finished && g.app_write_closed && g.to_socket.is_empty();
                drop(g);
                if drained && sockets.get::<tcp::Socket>(shared.handle).state() == tcp::State::Closed {
                    sockets.remove(shared.handle);
                    false
                } else {
                    true
                }
            });
        }

        // Driver exit (stop, Shutdown command, or the stopped flag): nothing will
        // ever service these conns again. Fail them ALL and broadcast — any thread
        // parked in read/write/wait_connected must get an error, not sleep forever
        // (each stranded waiter leaks a thread + loopback fds per reconnect).
        for shared in &conns {
            let mut g = shared.inner.lock().unwrap();
            g.failed = true;
            g.recv_finished = true;
            g.to_socket.clear();
            shared.cv.notify_all();
        }
    }

    /// Apply a Dial event (shared by the step-1 drain and the step-6 wait).
    fn exec_dial(
        &self,
        addr: IpEndpoint,
        reply: Sender<io::Result<Arc<ConnShared>>>,
        sockets: &mut SocketSet,
        iface: &mut Interface,
        conns: &mut Vec<Arc<ConnShared>>,
        next_local_port: &mut u16,
    ) {
        match self.open_socket(sockets, iface, addr, next_local_port) {
            Ok(handle) => {
                let shared = ConnShared::new(handle);
                conns.push(Arc::clone(&shared));
                let _ = reply.send(Ok(shared));
            }
            Err(e) => {
                let _ = reply.send(Err(e));
            }
        }
    }

    /// Apply a Resolve event (shared by the step-1 drain and the step-6 wait).
    fn exec_resolve(
        &self,
        host: String,
        reply: Sender<io::Result<IpAddr>>,
        sockets: &mut SocketSet,
        iface: &mut Interface,
        dns_handle: Option<SocketHandle>,
        dns_pending: &mut Vec<(dns::QueryHandle, StdInstant, Sender<io::Result<IpAddr>>)>,
    ) {
        match dns_handle {
            Some(h) => {
                let sock = sockets.get_mut::<dns::Socket>(h);
                match sock.start_query(iface.context(), &host, DnsQueryType::A) {
                    Ok(q) => {
                        log::debug!("wg: in-tunnel DNS query for {host}");
                        dns_pending.push((q, StdInstant::now(), reply));
                    }
                    Err(e) => {
                        let _ = reply.send(Err(io::Error::new(
                            io::ErrorKind::Other,
                            format!("DNS query failed: {e:?}"),
                        )));
                    }
                }
            }
            None => {
                let _ = reply.send(Err(io::Error::new(
                    io::ErrorKind::Unsupported,
                    "no WG DNS servers configured",
                )));
            }
        }
    }

    fn open_socket(
        &self,
        sockets: &mut SocketSet,
        iface: &mut Interface,
        addr: IpEndpoint,
        next_local_port: &mut u16,
    ) -> io::Result<SocketHandle> {
        let mut last_err = None;
        // Retry on ephemeral-port collision (a wrapped counter can land on a port
        // still held by a live socket).
        for _ in 0..8 {
            // 256 KiB per direction: per-conn throughput ceiling ≈ window/RTT, and
            // a WG-accelerated path's RTT is easily 100-400 ms — 64 KiB buffers
            // would cap a single upload at ~160-640 KB/s.
            let rx = tcp::SocketBuffer::new(vec![0u8; 256 * 1024]);
            let tx = tcp::SocketBuffer::new(vec![0u8; 256 * 1024]);
            let mut sock = tcp::Socket::new(rx, tx);
            sock.set_nagle_enabled(false);
            // Liveness: without these a SYN to a blackholed address retransmits
            // forever and a silently-dead session never errors — the socket (128
            // KiB of buffers) and its handler thread leak.
            sock.set_keep_alive(Some(smoltcp::time::Duration::from_secs(TCP_KEEPALIVE.as_secs())));
            sock.set_timeout(Some(smoltcp::time::Duration::from_secs(TCP_TIMEOUT.as_secs())));
            let local_port = *next_local_port;
            *next_local_port = if local_port == 65535 { 49152 } else { local_port + 1 };
            match sock.connect(iface.context(), addr, local_port) {
                Ok(()) => return Ok(sockets.add(sock)),
                Err(e) => last_err = Some(format!("{e:?}")),
            }
        }
        Err(io::Error::new(
            io::ErrorKind::Other,
            format!("connect: {}", last_err.unwrap_or_else(|| "no local port".into())),
        ))
    }

    /// decapsulate a WG datagram; flush any queued packets; feed IP to smoltcp.
    fn handle_incoming(
        &self,
        tunn: &mut Tunn,
        udp: &UdpSocket,
        datagram: &[u8],
        device: &mut TunDevice,
        scratch: &mut [u8],
    ) {
        let mut buf = [0u8; SCRATCH];
        match tunn.decapsulate(None, datagram, &mut buf) {
            TunnResult::WriteToNetwork(out) => {
                let _ = udp.send(out);
                // boringtun requires repeated empty calls to flush the queue.
                loop {
                    match tunn.decapsulate(None, &[], scratch) {
                        TunnResult::WriteToNetwork(more) => {
                            let _ = udp.send(more);
                        }
                        _ => break,
                    }
                }
            }
            TunnResult::WriteToTunnelV4(pkt, src) => {
                log::debug!("wg: ingress IP packet <- {} ({} B)", src, pkt.len());
                device.inbound.push_back(pkt.to_vec());
            }
            TunnResult::WriteToTunnelV6(pkt, src) => {
                log::debug!("wg: ingress IP packet <- {} ({} B)", src, pkt.len());
                device.inbound.push_back(pkt.to_vec());
            }
            TunnResult::Done => log::debug!("wg: decapsulate -> Done (keepalive/handshake, no payload)"),
            TunnResult::Err(e) => log::warn!("wg: decapsulate ERROR: {e:?} (can't decrypt peer data — key/session issue?)"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use smoltcp::iface::{Config, Interface, SocketSet};
    use smoltcp::socket::tcp;
    use smoltcp::wire::{HardwareAddress, IpAddress, IpCidr, IpEndpoint};

    fn keypair() -> (StaticSecret, PublicKey) {
        let sk = StaticSecret::random_from_rng(rand::rngs::OsRng);
        let pk = PublicKey::from(&sk);
        (sk, pk)
    }

    /// The tunnel interface is e.g. 10.94.1.5/32 but the upstream SOCKS (and any
    /// service) can live on a DIFFERENT remote subnet (192.168.94.x) reached
    /// through the WG peer. Confirm smoltcp actually routes such off-link targets
    /// out the device (i.e. into the tunnel) rather than dropping them.
    #[test]
    fn routes_offlink_remote_subnet_through_the_tunnel_device() {
        let mut device = TunDevice::new(DEFAULT_MTU);
        let mut config = Config::new(HardwareAddress::Ip);
        config.random_seed = 1;
        let mut iface = Interface::new(config, &mut device, SmolInstant::from_millis(0));

        let ifaddr: std::net::Ipv4Addr = "10.94.1.5".parse().unwrap();
        iface.update_ip_addrs(|a| {
            let _ = a.push(IpCidr::new(IpAddress::from(ifaddr), 32));
        });
        if let IpAddress::Ipv4(gw) = IpAddress::from(ifaddr) {
            let _ = iface.routes_mut().add_default_ipv4_route(gw);
        }

        let mut sockets = SocketSet::new(Vec::new());
        let rx = tcp::SocketBuffer::new(vec![0u8; 4096]);
        let tx = tcp::SocketBuffer::new(vec![0u8; 4096]);
        let mut sock = tcp::Socket::new(rx, tx);
        let target: std::net::Ipv4Addr = "192.168.94.10".parse().unwrap();
        sock.connect(iface.context(), IpEndpoint::new(IpAddress::from(target), 1080), 49152)
            .expect("connect should be accepted");
        let _h = sockets.add(sock);

        for i in 0..8 {
            iface.poll(SmolInstant::from_millis(i * 10), &mut device, &mut sockets);
        }

        let dsts: Vec<String> = device.outbound.iter().map(|p| ip_dst(p)).collect();
        assert!(
            dsts.iter().any(|d| d == "192.168.94.10"),
            "expected a SYN routed to the off-link target through the device; got {dsts:?}",
        );
        // The SYN's SOURCE must be the tunnel interface address (10.94.1.5), NOT
        // the phone's real NIC — otherwise the WG peer drops it by cryptokey routing.
        let syn = device
            .outbound
            .iter()
            .find(|p| ip_dst(p) == "192.168.94.10")
            .unwrap();
        assert_eq!(
            ip_src(syn),
            "10.94.1.5",
            "egress source must be the WG interface address",
        );
    }

    /// Drive two Tunns against each other purely in memory and confirm a WireGuard
    /// handshake completes and an IP packet survives the encrypt→decrypt round trip.
    #[test]
    fn wireguard_handshake_and_transport_roundtrip() {
        let (a_sk, a_pk) = keypair();
        let (b_sk, b_pk) = keypair();

        let mut a = Tunn::new(a_sk, b_pk, None, None, 0, None);
        let mut b = Tunn::new(b_sk, a_pk, None, None, 1, None);

        let mut buf = [0u8; SCRATCH];

        // A sends a data packet with no session → boringtun emits a handshake init.
        // boringtun validates the decrypted payload as a real IP packet, so build a
        // well-formed 40-byte IPv4 datagram (version 4, IHL 5, total length 40).
        let mut ip_packet = [0u8; 40];
        ip_packet[0] = 0x45; // version 4, IHL 5 (20-byte header)
        ip_packet[2] = 0x00;
        ip_packet[3] = 40; // total length
        ip_packet[8] = 64; // TTL
        ip_packet[9] = 6; // protocol = TCP
        ip_packet[12..16].copy_from_slice(&[10, 0, 0, 2]); // src
        ip_packet[16..20].copy_from_slice(&[10, 0, 0, 5]); // dst
        let init = match a.encapsulate(&ip_packet, &mut buf) {
            TunnResult::WriteToNetwork(d) => d.to_vec(),
            other => panic!("expected handshake init, got {other:?}"),
        };

        // B receives init → replies with handshake response.
        let mut buf2 = [0u8; SCRATCH];
        let resp = match b.decapsulate(None, &init, &mut buf2) {
            TunnResult::WriteToNetwork(d) => d.to_vec(),
            other => panic!("expected handshake response, got {other:?}"),
        };

        // A receives response → handshake completes; boringtun emits a keepalive to
        // confirm the session (the queued data packet drains on the next encapsulate).
        let mut buf3 = [0u8; SCRATCH];
        let keepalive = match a.decapsulate(None, &resp, &mut buf3) {
            TunnResult::WriteToNetwork(d) => d.to_vec(),
            other => panic!("expected session-confirming keepalive, got {other:?}"),
        };
        // Deliver the keepalive to B (no payload → Done).
        let mut buf4 = [0u8; SCRATCH];
        let _ = b.decapsulate(None, &keepalive, &mut buf4);

        assert!(a.stats().0.is_some(), "A should report a completed handshake");

        // With the session up, a fresh data packet must survive encrypt→decrypt.
        let mut buf5 = [0u8; SCRATCH];
        let data = match a.encapsulate(&ip_packet, &mut buf5) {
            TunnResult::WriteToNetwork(d) => d.to_vec(),
            other => panic!("expected encrypted data, got {other:?}"),
        };
        let mut buf6 = [0u8; SCRATCH];
        match b.decapsulate(None, &data, &mut buf6) {
            TunnResult::WriteToTunnelV4(pkt, _) | TunnResult::WriteToTunnelV6(pkt, _) => {
                assert_eq!(pkt, &ip_packet, "payload must survive the tunnel");
            }
            other => panic!("expected tunnelled packet, got {other:?}"),
        }
    }

    #[test]
    fn generated_private_key_is_clamped_like_wg_genkey() {
        let kp = generate_keypair();
        let sk = decode_key(&kp.private_key).unwrap();
        // RFC 7748 clamping bits, exactly what `wg genkey` sets.
        assert_eq!(sk[0] & 0b0000_0111, 0, "low 3 bits must be clear");
        assert_eq!(sk[31] & 0b1000_0000, 0, "high bit must be clear");
        assert_eq!(sk[31] & 0b0100_0000, 0b0100_0000, "bit 254 must be set");
        // Clamping the stored private key must NOT change the derived public key.
        assert_eq!(derive_public_key(&kp.private_key).unwrap(), kp.public_key);
    }

    #[test]
    fn generated_keypair_is_valid_and_parses() {
        let kp = generate_keypair();
        // Both keys decode to 32 bytes and the pair round-trips through parse().
        assert_eq!(decode_key(&kp.private_key).unwrap().len(), 32);
        assert_eq!(decode_key(&kp.public_key).unwrap().len(), 32);
        WgParams::parse(&kp.private_key, &kp.public_key, None, "1.2.3.4:51820", &["10.0.0.2/32".into()], &[], 25, 0)
            .expect("generated keys should parse");
    }

    #[test]
    fn dns_a_query_roundtrips_through_the_wire_parser() {
        // Build an A query, craft a matching response, and parse the A record.
        let q = build_a_query("www.google.com");
        // A well-formed response: echo the question, add one A answer 142.250.1.2.
        let mut resp = Vec::new();
        resp.extend_from_slice(&[0x12, 0x34]); // id
        resp.extend_from_slice(&[0x81, 0x80]); // QR=1, RCODE=0
        resp.extend_from_slice(&[0x00, 0x01]); // QD
        resp.extend_from_slice(&[0x00, 0x01]); // AN
        resp.extend_from_slice(&[0, 0, 0, 0]); // NS/AR
        resp.extend_from_slice(&q[12..q.len()]); // question (name+type+class)
        resp.extend_from_slice(&[0xC0, 0x0C]); // answer name: pointer to question
        resp.extend_from_slice(&[0x00, 0x01, 0x00, 0x01]); // type A, class IN
        resp.extend_from_slice(&[0, 0, 1, 0x2C]); // TTL
        resp.extend_from_slice(&[0x00, 0x04]); // RDLENGTH
        resp.extend_from_slice(&[142, 250, 1, 2]); // A record
        assert_eq!(
            parse_a_record(&resp),
            Some(IpAddr::V4(std::net::Ipv4Addr::new(142, 250, 1, 2))),
        );
    }

    #[test]
    fn parse_accepts_bare_interface_ip_as_slash32() {
        let (sk, _) = keypair();
        let (_, pk) = keypair();
        let sk_b64 = base64::engine::general_purpose::STANDARD.encode(sk.to_bytes());
        let pk_b64 = base64::engine::general_purpose::STANDARD.encode(pk.to_bytes());
        // A user typing "10.94.1.200" (no /32) must become 10.94.1.200/32, not a default.
        let p = WgParams::parse(&sk_b64, &pk_b64, None, "1.2.3.4:51820", &["10.94.1.200".into()], &[], 25, 0)
            .expect("bare interface IP should parse");
        assert_eq!(p.interface_addrs.len(), 1);
        assert_eq!(p.interface_addrs[0].address().to_string(), "10.94.1.200");
        assert_eq!(p.interface_addrs[0].prefix_len(), 32);
    }

    #[test]
    fn parse_rejects_bad_key() {
        assert!(WgParams::parse("not-base64!!", "AAAA", None, "1.2.3.4:51820", &["10.0.0.2/32".into()], &[], 25, 0).is_err());
    }

    /// Full end-to-end smoke test: a REAL WgTunnel (driver thread, smoltcp,
    /// boringtun, UDP loopback) against a scripted in-memory WG server (a second
    /// Tunn + a server-side smoltcp stack running a TCP echo listener).
    /// Guards the whole data path — dial, connect-state transitions, write,
    /// read-back — the class of bug where every connection fails instantly.
    #[test]
    fn end_to_end_tcp_echo_through_live_tunnel() {
        let (c_sk, c_pk) = keypair();
        let (s_sk, s_pk) = keypair();

        // The scripted server: UDP socket + Tunn + smoltcp with an echo listener.
        let server_udp = UdpSocket::bind(("127.0.0.1", 0)).unwrap();
        server_udp.set_read_timeout(Some(Duration::from_millis(2))).unwrap();
        let server_port = server_udp.local_addr().unwrap().port();
        let stop = Arc::new(AtomicBool::new(false));
        let stop2 = Arc::clone(&stop);
        let server = std::thread::spawn(move || {
            run_echo_server(server_udp, s_sk, c_pk, stop2);
        });

        // The real client tunnel, aimed at the scripted server.
        let params = WgParams::parse(
            &base64::engine::general_purpose::STANDARD.encode(c_sk.to_bytes()),
            &base64::engine::general_purpose::STANDARD.encode(s_pk.to_bytes()),
            None,
            &format!("127.0.0.1:{server_port}"),
            &["10.99.0.2/32".into()],
            &[],
            25,
            0,
        )
        .unwrap();
        let tunnel = WgTunnel::start(params).unwrap();

        // Dial the echo listener THROUGH the tunnel (10.99.0.1 = server interface).
        let deadline = StdInstant::now() + Duration::from_secs(20);
        let mut stream = loop {
            match tunnel.dial("10.99.0.1", 4321) {
                Ok(s) => break s,
                Err(e) => {
                    assert!(StdInstant::now() < deadline, "dial never succeeded: {e}");
                    std::thread::sleep(Duration::from_millis(500));
                }
            }
        };
        stream.write_all(b"ping").unwrap();
        let mut buf = [0u8; 4];
        stream.read_exact(&mut buf).unwrap();
        assert_eq!(&buf, b"ping", "echo must round-trip through the live tunnel");

        tunnel.shutdown();
        stop.store(true, Ordering::SeqCst);
        let _ = server.join();
    }

    /// The scripted server: decapsulate client datagrams, feed IP into a
    /// server-side smoltcp stack, echo TCP payloads on port 4321, encapsulate
    /// replies back. Runs until `stop` is set.
    fn run_echo_server(udp: UdpSocket, sk: StaticSecret, client_pk: PublicKey, stop: Arc<AtomicBool>) {
        let mut tunn = Tunn::new(sk, client_pk, None, None, 1, None);

        let mut device = TunDevice::new(DEFAULT_MTU);
        let mut config = Config::new(HardwareAddress::Ip);
        config.random_seed = 42;
        let mut iface = Interface::new(config, &mut device, SmolInstant::from_millis(0));
        iface.update_ip_addrs(|a| {
            let _ = a.push(IpCidr::new(IpAddress::from(smoltcp::wire::Ipv4Address::new(10, 99, 0, 1)), 32));
        });
        let _ = iface
            .routes_mut()
            .add_default_ipv4_route(smoltcp::wire::Ipv4Address::new(10, 99, 0, 1).into());

        let mut sockets = SocketSet::new(Vec::new());
        let rx = tcp::SocketBuffer::new(vec![0u8; 4096]);
        let tx = tcp::SocketBuffer::new(vec![0u8; 4096]);
        let mut listener = tcp::Socket::new(rx, tx);
        listener.listen(4321).unwrap();
        let echo_handle = sockets.add(listener);

        let smol_start = StdInstant::now();
        let mut scratch = [0u8; SCRATCH];
        let mut peer_addr: Option<SocketAddr> = None;

        while !stop.load(Ordering::SeqCst) {
            // WG datagrams in.
            let mut buf = [0u8; SCRATCH];
            match udp.recv_from(&mut buf) {
                Ok((n, from)) => {
                    peer_addr = Some(from);
                    let mut tmp = [0u8; SCRATCH];
                    match tunn.decapsulate(None, &buf[..n], &mut tmp) {
                        TunnResult::WriteToNetwork(out) => {
                            let _ = udp.send_to(out, from);
                            loop {
                                match tunn.decapsulate(None, &[], &mut tmp) {
                                    TunnResult::WriteToNetwork(more) => {
                                        let _ = udp.send_to(more, from);
                                    }
                                    _ => break,
                                }
                            }
                        }
                        TunnResult::WriteToTunnelV4(pkt, _) | TunnResult::WriteToTunnelV6(pkt, _) => {
                            device.inbound.push_back(pkt.to_vec());
                        }
                        _ => {}
                    }
                }
                Err(ref e)
                    if matches!(
                        e.kind(),
                        io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut | io::ErrorKind::Interrupted
                    ) => {}
                Err(_) => break,
            }

            // Drive the server TCP stack; echo anything received.
            let now = SmolInstant::from_micros(smol_start.elapsed().as_micros() as i64);
            iface.poll(now, &mut device, &mut sockets);
            {
                let sock = sockets.get_mut::<tcp::Socket>(echo_handle);
                if sock.can_recv() && sock.can_send() {
                    let mut data = [0u8; 4096];
                    if let Ok(n) = sock.recv_slice(&mut data) {
                        if n > 0 {
                            let _ = sock.send_slice(&data[..n]);
                        }
                    }
                }
            }

            // Server packets out: encapsulate → client.
            while let Some(pkt) = device.outbound.pop_front() {
                if let TunnResult::WriteToNetwork(out) = tunn.encapsulate(&pkt, &mut scratch) {
                    if let Some(to) = peer_addr {
                        let _ = udp.send_to(out, to);
                    }
                }
            }

            // WG timers (rekey/keepalive responses).
            if let TunnResult::WriteToNetwork(out) = tunn.update_timers(&mut scratch) {
                if let Some(to) = peer_addr {
                    let _ = udp.send_to(out, to);
                }
            }
        }
    }

    #[test]
    fn parse_accepts_valid_config() {
        let (sk, _) = keypair();
        let (_, pk) = keypair();
        let sk_b64 = base64::engine::general_purpose::STANDARD.encode(sk.to_bytes());
        let pk_b64 = base64::engine::general_purpose::STANDARD.encode(pk.to_bytes());
        let p = WgParams::parse(&sk_b64, &pk_b64, None, "10.0.0.1:51820", &["10.0.0.2/32".into()], &[], 25, 0)
            .expect("valid config should parse");
        assert_eq!(p.endpoint.port(), 51820);
        assert_eq!(p.keepalive_secs, Some(25));
    }
}
