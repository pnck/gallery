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
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Condvar, Mutex};
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

/// Max IP packet we shuttle through the tunnel (WG adds ~32B overhead below this).
const MTU: usize = 1420;
/// Scratch buffer for boringtun in/out (must fit MTU + WG framing).
const SCRATCH: usize = 2048;
/// Per-connection app↔socket buffer high-water mark (backpressure).
const CONN_BUF_CAP: usize = 256 * 1024;
/// Driver tick: bounds how quickly app writes/timers are serviced. WireGuard
/// timers need servicing a few times per second; this also caps write latency.
const TICK: Duration = Duration::from_millis(5);
/// Fallback DNS resolvers when the WG config specifies none (design semantic).
const DEFAULT_DNS: [&str; 2] = ["1.1.1.1", "8.8.8.8"];

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
}

impl TunDevice {
    fn new() -> Self {
        TunDevice { inbound: VecDeque::new(), outbound: VecDeque::new() }
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
        caps.max_transmission_unit = MTU;
        caps
    }
}

// ── Per-connection shared state (driver ↔ application thread) ────────────────

#[derive(PartialEq)]
enum ConnStatus {
    Connecting,
    Connected,
    Failed,
}

struct ConnShared {
    handle: SocketHandle,
    inner: Mutex<ConnInner>,
    cv: Condvar,
}

struct ConnInner {
    status: ConnStatus,
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
                status: ConnStatus::Connecting,
                to_socket: VecDeque::new(),
                from_socket: VecDeque::new(),
                app_write_closed: false,
                recv_finished: false,
            }),
            cv: Condvar::new(),
        })
    }
}

// ── Commands from application threads to the driver ──────────────────────────

enum Command {
    Dial {
        addr: IpEndpoint,
        reply: Sender<io::Result<Arc<ConnShared>>>,
    },
    /// Resolve a hostname over the tunnel using the WG-configured DNS servers.
    Resolve {
        host: String,
        reply: Sender<io::Result<IpAddr>>,
    },
    Shutdown,
}

/// Handle to the running tunnel. Cloneable; the last drop stops the driver.
pub struct WgTunnel {
    cmd: Sender<Command>,
    /// Effective DNS servers: the WG-configured ones, or our defaults if none were
    /// given (design semantic). Used by both the UDP and TCP in-tunnel resolvers.
    dns_servers: Vec<IpAddr>,
    handshake_ok: Arc<AtomicBool>,
    last_handshake_epoch: Arc<AtomicI64>,
    tx_bytes: Arc<AtomicU64>,
    rx_bytes: Arc<AtomicU64>,
    stopped: Arc<AtomicBool>,
}

impl WgTunnel {
    /// Build the tunnel and spawn its driver thread. Returns once the netstack is
    /// up; the WireGuard handshake completes asynchronously on first traffic.
    pub fn start(mut params: WgParams) -> io::Result<WgTunnel> {
        let udp = UdpSocket::bind(("0.0.0.0", 0))?;
        udp.connect(params.endpoint)?;
        udp.set_read_timeout(Some(TICK))?;

        // Design semantic: use the configured [Interface] DNS if given, else our
        // defaults. Both the UDP (smoltcp) and TCP resolvers query these.
        if params.dns_servers.is_empty() {
            params.dns_servers = DEFAULT_DNS
                .iter()
                .map(|s| IpAddress::from(s.parse::<std::net::Ipv4Addr>().unwrap()))
                .collect();
        }
        let dns_servers: Vec<IpAddr> = params.dns_servers.iter().map(|a| IpAddr::from(*a)).collect();

        let (cmd_tx, cmd_rx) = mpsc::channel::<Command>();
        let handshake_ok = Arc::new(AtomicBool::new(false));
        let last_handshake_epoch = Arc::new(AtomicI64::new(0));
        let tx_bytes = Arc::new(AtomicU64::new(0));
        let rx_bytes = Arc::new(AtomicU64::new(0));
        let stopped = Arc::new(AtomicBool::new(false));

        let driver = Driver {
            cmd_rx,
            handshake_ok: Arc::clone(&handshake_ok),
            last_handshake_epoch: Arc::clone(&last_handshake_epoch),
            tx_bytes: Arc::clone(&tx_bytes),
            rx_bytes: Arc::clone(&rx_bytes),
            stopped: Arc::clone(&stopped),
        };
        std::thread::Builder::new()
            .name("gallery-wg-driver".into())
            .spawn(move || driver.run(params, udp))
            .map_err(|e| io::Error::new(io::ErrorKind::Other, e.to_string()))?;

        Ok(WgTunnel {
            cmd: cmd_tx,
            dns_servers,
            handshake_ok,
            last_handshake_epoch,
            tx_bytes,
            rx_bytes,
            stopped,
        })
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
        self.cmd
            .send(Command::Dial { addr, reply: tx })
            .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "tunnel driver stopped"))?;
        let shared = rx
            .recv()
            .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "tunnel driver stopped"))??;

        let stream = TunnelStream {
            shared,
            cmd: self.cmd.clone(),
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
        self.cmd
            .send(Command::Resolve { host: host.to_string(), reply: tx })
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
        self.stopped.store(true, Ordering::SeqCst);
        let _ = self.cmd.send(Command::Shutdown);
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

// ── The application-facing blocking stream ───────────────────────────────────

/// A blocking, TcpStream-like handle to one tunnelled TCP connection.
pub struct TunnelStream {
    shared: Arc<ConnShared>,
    cmd: Sender<Command>,
}

impl TunnelStream {
    fn wait_connected(&self) -> io::Result<()> {
        let mut g = self.shared.inner.lock().unwrap();
        loop {
            match g.status {
                ConnStatus::Connected => return Ok(()),
                ConnStatus::Failed => {
                    return Err(io::Error::new(
                        io::ErrorKind::ConnectionRefused,
                        "tunnel TCP connect failed",
                    ))
                }
                ConnStatus::Connecting => g = self.shared.cv.wait(g).unwrap(),
            }
        }
    }
}

impl Read for TunnelStream {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let mut g = self.shared.inner.lock().unwrap();
        loop {
            if !g.from_socket.is_empty() {
                let n = g.from_socket.len().min(buf.len());
                for b in buf.iter_mut().take(n) {
                    *b = g.from_socket.pop_front().unwrap();
                }
                return Ok(n);
            }
            if g.recv_finished || g.status == ConnStatus::Failed {
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
            if g.status == ConnStatus::Failed {
                return Err(io::Error::new(io::ErrorKind::BrokenPipe, "tunnel connection failed"));
            }
            if g.to_socket.len() < CONN_BUF_CAP {
                g.to_socket.extend(buf.iter().copied());
                self.shared.cv.notify_all();
                return Ok(buf.len());
            }
            // Backpressure: wait for the driver to drain into the socket.
            g = self.shared.cv.wait(g).unwrap();
        }
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

impl Clone for TunnelStream {
    fn clone(&self) -> Self {
        TunnelStream {
            shared: Arc::clone(&self.shared),
            cmd: self.cmd.clone(),
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
    }
}

// ── The single-threaded driver ───────────────────────────────────────────────

struct Driver {
    cmd_rx: Receiver<Command>,
    handshake_ok: Arc<AtomicBool>,
    last_handshake_epoch: Arc<AtomicI64>,
    tx_bytes: Arc<AtomicU64>,
    rx_bytes: Arc<AtomicU64>,
    stopped: Arc<AtomicBool>,
}

impl Driver {
    fn run(self, params: WgParams, udp: UdpSocket) {
        let mut tunn = Tunn::new(
            params.private_key.clone(),
            params.peer_public_key,
            params.preshared_key,
            params.keepalive_secs,
            0,
            None,
        );

        let mut device = TunDevice::new();
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

        loop {
            if self.stopped.load(Ordering::SeqCst) {
                break;
            }

            // 1. Drain commands (non-blocking).
            while let Ok(cmd) = self.cmd_rx.try_recv() {
                match cmd {
                    Command::Shutdown => return,
                    Command::Dial { addr, reply } => {
                        let res = self.open_socket(
                            &mut sockets,
                            &mut iface,
                            addr,
                            &mut next_local_port,
                        );
                        match res {
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
                    Command::Resolve { host, reply } => match dns_handle {
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
                    },
                }
            }

            // 2. App → socket: move queued app bytes into smoltcp send buffers,
            //    and close sockets whose app write half is done.
            for shared in &conns {
                let sock = sockets.get_mut::<tcp::Socket>(shared.handle);
                let mut g = shared.inner.lock().unwrap();
                while sock.can_send() && !g.to_socket.is_empty() {
                    let chunk: Vec<u8> = g.to_socket.iter().copied().collect();
                    match sock.send_slice(&chunk) {
                        Ok(0) => break,
                        Ok(n) => {
                            g.to_socket.drain(..n);
                        }
                        Err(_) => break,
                    }
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

            // 4. Socket → app: pull decoded bytes out to app read buffers; update state.
            for shared in &conns {
                let sock = sockets.get_mut::<tcp::Socket>(shared.handle);
                let mut g = shared.inner.lock().unwrap();
                let mut changed = false;
                if sock.is_active() && sock.may_recv() && g.status == ConnStatus::Connecting {
                    g.status = ConnStatus::Connected;
                    changed = true;
                }
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
                    // Peer FIN or connection gone.
                    if sock.state() == tcp::State::Closed && g.status == ConnStatus::Connecting {
                        g.status = ConnStatus::Failed;
                    }
                    g.recv_finished = true;
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

            // 6. Read any WG datagrams from the peer, decapsulate into IP packets.
            let mut recv_buf = [0u8; SCRATCH];
            loop {
                match udp.recv(&mut recv_buf) {
                    Ok(n) => {
                        log::debug!("wg: <- udp {n} B from peer");
                        self.handle_incoming(&mut tunn, &udp, &recv_buf[..n], &mut device, &mut scratch);
                    }
                    // WouldBlock/TimedOut: drain done for this tick. Interrupted
                    // (EINTR, e.g. a signal during recv) is transient — just stop
                    // draining; the next driver tick re-reads. None are fatal.
                    Err(ref e)
                        if matches!(
                            e.kind(),
                            io::ErrorKind::WouldBlock
                                | io::ErrorKind::TimedOut
                                | io::ErrorKind::Interrupted
                        ) =>
                    {
                        break
                    }
                    Err(e) => {
                        log::warn!("wg udp recv error: {e}");
                        break;
                    }
                }
            }

            // 7. WireGuard timers (handshake init, keepalive, rekey). boringtun's
            //    timers are second-granular; throttle to ~250 ms instead of every
            //    ~5 ms tick (it also resets the rate limiter on every call).
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
            // Log establish/lose transitions and keep re-initiating until up.
            if ok && !established {
                established = true;
                log::info!("wg: handshake established");
            } else if !ok && established {
                established = false;
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
            //    smoltcp sockets (each holds 128 KiB of buffers).
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
    }

    fn open_socket(
        &self,
        sockets: &mut SocketSet,
        iface: &mut Interface,
        addr: IpEndpoint,
        next_local_port: &mut u16,
    ) -> io::Result<SocketHandle> {
        let rx = tcp::SocketBuffer::new(vec![0u8; 64 * 1024]);
        let tx = tcp::SocketBuffer::new(vec![0u8; 64 * 1024]);
        let mut sock = tcp::Socket::new(rx, tx);
        sock.set_nagle_enabled(false);
        let local_port = *next_local_port;
        *next_local_port = next_local_port.checked_add(1).unwrap_or(49152);
        sock.connect(iface.context(), addr, local_port)
            .map_err(|e| io::Error::new(io::ErrorKind::Other, format!("connect: {e:?}")))?;
        Ok(sockets.add(sock))
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
        let mut device = TunDevice::new();
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
        WgParams::parse(&kp.private_key, &kp.public_key, None, "1.2.3.4:51820", &["10.0.0.2/32".into()], &[], 25)
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
        let p = WgParams::parse(&sk_b64, &pk_b64, None, "1.2.3.4:51820", &["10.94.1.200".into()], &[], 25)
            .expect("bare interface IP should parse");
        assert_eq!(p.interface_addrs.len(), 1);
        assert_eq!(p.interface_addrs[0].address().to_string(), "10.94.1.200");
        assert_eq!(p.interface_addrs[0].prefix_len(), 32);
    }

    #[test]
    fn parse_rejects_bad_key() {
        assert!(WgParams::parse("not-base64!!", "AAAA", None, "1.2.3.4:51820", &["10.0.0.2/32".into()], &[], 25).is_err());
    }

    #[test]
    fn parse_accepts_valid_config() {
        let (sk, _) = keypair();
        let (_, pk) = keypair();
        let sk_b64 = base64::engine::general_purpose::STANDARD.encode(sk.to_bytes());
        let pk_b64 = base64::engine::general_purpose::STANDARD.encode(pk.to_bytes());
        let p = WgParams::parse(&sk_b64, &pk_b64, None, "10.0.0.1:51820", &["10.0.0.2/32".into()], &[], 25)
            .expect("valid config should parse");
        assert_eq!(p.endpoint.port(), 51820);
        assert_eq!(p.keepalive_secs, Some(25));
    }
}
