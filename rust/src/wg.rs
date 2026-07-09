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
use smoltcp::socket::tcp;
use smoltcp::time::Instant as SmolInstant;
use smoltcp::wire::{HardwareAddress, IpAddress, IpCidr, IpEndpoint};

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

/// Parsed, validated WireGuard parameters (from CoreConfig::WgThenSocks).
#[derive(Clone)]
pub struct WgParams {
    pub private_key: StaticSecret,
    pub peer_public_key: PublicKey,
    pub preshared_key: Option<[u8; 32]>,
    pub endpoint: SocketAddr,
    /// The tunnel-interior interface address(es), e.g. 10.0.0.2/32.
    pub interface_addrs: Vec<IpCidr>,
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
        Ok(WgParams {
            private_key: StaticSecret::from(sk),
            peer_public_key: PublicKey::from(pk),
            preshared_key: psk,
            endpoint,
            interface_addrs: addrs,
            keepalive_secs: if keepalive_secs == 0 { None } else { Some(keepalive_secs) },
        })
    }
}

/// Generate a WireGuard keypair (base64), equivalent to `wg genkey`/`wg pubkey`.
pub fn generate_keypair() -> crate::WireguardKeypair {
    let sk = StaticSecret::random_from_rng(rand::rngs::OsRng);
    let pk = PublicKey::from(&sk);
    let enc = base64::engine::general_purpose::STANDARD;
    crate::WireguardKeypair {
        private_key: enc.encode(sk.to_bytes()),
        public_key: enc.encode(pk.to_bytes()),
    }
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
    Shutdown,
}

/// Handle to the running tunnel. Cloneable; the last drop stops the driver.
pub struct WgTunnel {
    cmd: Sender<Command>,
    handshake_ok: Arc<AtomicBool>,
    last_handshake_epoch: Arc<AtomicI64>,
    tx_bytes: Arc<AtomicU64>,
    rx_bytes: Arc<AtomicU64>,
    stopped: Arc<AtomicBool>,
}

impl WgTunnel {
    /// Build the tunnel and spawn its driver thread. Returns once the netstack is
    /// up; the WireGuard handshake completes asynchronously on first traffic.
    pub fn start(params: WgParams) -> io::Result<WgTunnel> {
        let udp = UdpSocket::bind(("0.0.0.0", 0))?;
        udp.connect(params.endpoint)?;
        udp.set_read_timeout(Some(TICK))?;

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

        // WireGuard is lazy: without traffic it never initiates the first handshake,
        // so the peer would never see us. Proactively initiate now (and re-initiate
        // below until established), like a normal client with persistent keepalive.
        log::info!("wg: driver up, initiating handshake");
        initiate_handshake(&mut tunn, &udp, &mut scratch);
        let mut last_hs_attempt = StdInstant::now();
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
                log::debug!("wg: egress IP packet -> {} ({} B)", ip_dst(&pkt), pkt.len());
                match tunn.encapsulate(&pkt, &mut scratch) {
                    TunnResult::WriteToNetwork(out) => {
                        let _ = udp.send(out);
                    }
                    TunnResult::Done => {} // queued pending handshake
                    TunnResult::Err(e) => log::debug!("wg encapsulate error: {e:?}"),
                    _ => {}
                }
            }

            // 6. Read any WG datagrams from the peer, decapsulate into IP packets.
            let mut recv_buf = [0u8; SCRATCH];
            loop {
                match udp.recv(&mut recv_buf) {
                    Ok(n) => self.handle_incoming(&mut tunn, &udp, &recv_buf[..n], &mut device, &mut scratch),
                    Err(ref e) if e.kind() == io::ErrorKind::WouldBlock || e.kind() == io::ErrorKind::TimedOut => break,
                    Err(e) => {
                        log::warn!("wg udp recv error: {e}");
                        break;
                    }
                }
            }

            // 7. WireGuard timers (handshake init, keepalive, rekey).
            match tunn.update_timers(&mut scratch) {
                TunnResult::WriteToNetwork(out) => {
                    let _ = udp.send(out);
                }
                TunnResult::Err(e) => log::debug!("wg timer error: {e:?}"),
                _ => {}
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
            TunnResult::Done => {}
            TunnResult::Err(e) => log::debug!("wg decapsulate error: {e:?}"),
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
    fn generated_keypair_is_valid_and_parses() {
        let kp = generate_keypair();
        // Both keys decode to 32 bytes and the pair round-trips through parse().
        assert_eq!(decode_key(&kp.private_key).unwrap().len(), 32);
        assert_eq!(decode_key(&kp.public_key).unwrap().len(), 32);
        WgParams::parse(&kp.private_key, &kp.public_key, None, "1.2.3.4:51820", &["10.0.0.2/32".into()], 25)
            .expect("generated keys should parse");
    }

    #[test]
    fn parse_rejects_bad_key() {
        assert!(WgParams::parse("not-base64!!", "AAAA", None, "1.2.3.4:51820", &["10.0.0.2/32".into()], 25).is_err());
    }

    #[test]
    fn parse_accepts_valid_config() {
        let (sk, _) = keypair();
        let (_, pk) = keypair();
        let sk_b64 = base64::engine::general_purpose::STANDARD.encode(sk.to_bytes());
        let pk_b64 = base64::engine::general_purpose::STANDARD.encode(pk.to_bytes());
        let p = WgParams::parse(&sk_b64, &pk_b64, None, "10.0.0.1:51820", &["10.0.0.2/32".into()], 25)
            .expect("valid config should parse");
        assert_eq!(p.endpoint.port(), 51820);
        assert_eq!(p.keepalive_secs, Some(25));
    }
}
