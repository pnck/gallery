//! Minimal SOCKS5 inbound + outbound dialer (RFC 1928), std-only.
//!
//! Inbound: no-auth SOCKS5 accepting CONNECT to IPv4 / IPv6 / domain targets.
//! Outbound: Direct dial, a chain to an upstream SOCKS5, or (phase 2) that same
//! upstream SOCKS5 reached *through* the userspace WireGuard tunnel. The hostname
//! is preserved end-to-end so DNS resolves at the far end (remote DNS, §4.2).
//!
//! The outbound connection is abstracted as a [`Conn`] (Read + Write + a
//! TcpStream-like clone) so the SOCKS5 client handshake and the bidirectional
//! splice work identically over a real socket or a tunnel-backed stream.

use crate::CoreConfig;
use std::io::{self, Read, Write};
use std::net::{TcpListener, TcpStream, ToSocketAddrs};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use crate::wg::WgTunnel;

const VER: u8 = 0x05;
const CMD_CONNECT: u8 = 0x01;
const ATYP_IPV4: u8 = 0x01;
const ATYP_DOMAIN: u8 = 0x03;
const ATYP_IPV6: u8 = 0x04;
const REP_OK: u8 = 0x00;
const REP_GENERAL_FAIL: u8 = 0x01;

/// Target address, kept as sent (domains are NOT resolved locally).
#[derive(Clone, Debug)]
pub enum Target {
    Ip(std::net::IpAddr, u16),
    Domain(String, u16),
}

/// A duplex byte stream that can be split into two owned halves for the splice
/// loop — mirroring [`TcpStream::try_clone`]. Implemented by real sockets and by
/// the WireGuard tunnel stream.
pub trait Conn: Read + Write + Send {
    fn try_clone_box(&self) -> io::Result<Box<dyn Conn>>;
    /// Half-close the write direction (SOCKS splice signals EOF this way).
    fn shutdown_write(&self);
}

impl Conn for TcpStream {
    fn try_clone_box(&self) -> io::Result<Box<dyn Conn>> {
        Ok(Box::new(self.try_clone()?))
    }
    fn shutdown_write(&self) {
        let _ = self.shutdown(std::net::Shutdown::Write);
    }
}

#[derive(Clone)]
pub enum Dialer {
    Direct,
    Socks {
        host: String,
        port: u16,
        auth: Option<(String, String)>,
    },
    /// Dial the target directly through the WireGuard tunnel (WG only, no SOCKS).
    WgDirect { tunnel: Arc<WgTunnel> },
    /// Reach the upstream SOCKS5 through the WireGuard tunnel (phase 2, T-502).
    WgThenSocks {
        tunnel: Arc<WgTunnel>,
        host: String,
        port: u16,
        auth: Option<(String, String)>,
    },
}

impl Dialer {
    pub fn from_config(config: &CoreConfig) -> Dialer {
        match config {
            CoreConfig::Direct => Dialer::Direct,
            CoreConfig::SocksUpstream {
                host,
                port,
                username,
                password,
            } => Dialer::Socks {
                host: host.clone(),
                port: *port,
                auth: pair(username, password),
            },
            // WG modes are assembled in WgCore::start (they need the live tunnel);
            // they never arrive here as a bare config.
            CoreConfig::WgOnly { .. } | CoreConfig::WgThenSocks { .. } => Dialer::Direct,
        }
    }

    fn dial(&self, target: &Target) -> io::Result<Box<dyn Conn>> {
        match self {
            Dialer::Direct => Ok(Box::new(dial_direct(target)?)),
            Dialer::WgDirect { tunnel } => {
                // Resolve the target via DNS-over-TCP THROUGH the tunnel (UDP DNS
                // can't survive a transparent-SOCKS exit). IP literals need none.
                let (ip, port) = match target {
                    Target::Ip(ip, port) => (*ip, *port),
                    Target::Domain(host, port) => {
                        let ip = dual_resolve_via_tunnel(tunnel, host).or_else(|e| {
                            log::warn!("wg: in-tunnel DNS for {host} failed ({e}); local fallback (DNS leak)");
                            resolve_for_tunnel(target).map(|(ip, _)| ip)
                        })?;
                        (ip, *port)
                    }
                };
                Ok(Box::new(tunnel.dial(&ip.to_string(), port)?))
            }
            Dialer::Socks { host, port, auth } => {
                let mut up = TcpStream::connect((host.as_str(), *port))?;
                up.set_nodelay(true).ok();
                up.set_read_timeout(Some(Duration::from_secs(30)))?;
                socks5_client_handshake(&mut up, auth.as_ref(), target)?;
                // Handshake done; the persistent splice must not time out on idle.
                up.set_read_timeout(None).ok();
                Ok(Box::new(up))
            }
            Dialer::WgThenSocks {
                tunnel,
                host,
                port,
                auth,
            } => {
                // Dial the upstream SOCKS5's IP:port *inside* the tunnel, then run
                // the identical SOCKS5 client handshake over the tunnel stream.
                // A hostname upstream (e.g. "nas.home") is resolved via in-tunnel
                // DNS first — WgTunnel::dial only accepts IP literals, so without
                // this every single request failed with an opaque "general failure".
                let ip = match host.parse::<std::net::IpAddr>() {
                    Ok(ip) => ip.to_string(),
                    Err(_) => dual_resolve_via_tunnel(tunnel, host)
                        .map_err(|e| {
                            io::Error::new(
                                io::ErrorKind::NotFound,
                                format!("in-tunnel DNS for upstream '{host}' failed: {e}"),
                            )
                        })?
                        .to_string(),
                };
                let mut up = tunnel.dial(&ip, *port)?;
                socks5_client_handshake(&mut up, auth.as_ref(), target)?;
                Ok(Box::new(up))
            }
        }
    }
}

/// Resolve `host` through the tunnel with **redundant DNS-over-TCP + DNS-over-UDP**
/// queries fired concurrently — the first success wins. TCP (public resolvers)
/// survives a transparent-SOCKS exit; UDP (the WG-configured server via smoltcp)
/// works where UDP DNS is reachable. Bounded by an 8s timeout on scratch threads
/// so the caller never hangs.
fn dual_resolve_via_tunnel(tunnel: &Arc<WgTunnel>, host: &str) -> io::Result<std::net::IpAddr> {
    let (tx, rx) = std::sync::mpsc::channel();

    // DNS-over-TCP to public resolvers, through the tunnel.
    {
        let (t, h, tx) = (Arc::clone(tunnel), host.to_string(), tx.clone());
        std::thread::spawn(move || {
            let _ = tx.send(crate::wg::tcp_dns_resolve(&t, &h));
        });
    }
    // DNS-over-UDP to the WG-configured server (smoltcp DNS socket).
    {
        let (t, h, tx) = (Arc::clone(tunnel), host.to_string(), tx);
        std::thread::spawn(move || {
            let r = t
                .resolve_or(&h)
                .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "udp dns: no answer/not configured"));
            let _ = tx.send(r);
        });
    }

    // First Ok wins; tolerate one path failing while the other is still running.
    let start = Instant::now();
    let budget = Duration::from_secs(8);
    let mut last = io::Error::new(io::ErrorKind::TimedOut, "in-tunnel DNS timed out");
    loop {
        let elapsed = start.elapsed();
        if elapsed >= budget {
            return Err(last);
        }
        match rx.recv_timeout(budget - elapsed) {
            Ok(Ok(ip)) => return Ok(ip),
            Ok(Err(e)) => last = e, // one path failed; keep waiting for the other
            Err(_) => return Err(last), // timeout or both disconnected
        }
    }
}

/// Resolve a target to an IP:port for tunnel dialing. IP targets pass through;
/// domains are resolved locally (WgDirect has no upstream to do remote DNS).
fn resolve_for_tunnel(target: &Target) -> io::Result<(std::net::IpAddr, u16)> {
    match target {
        Target::Ip(ip, port) => Ok((*ip, *port)),
        Target::Domain(host, port) => {
            let addr = (host.as_str(), *port)
                .to_socket_addrs()?
                .next()
                .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "no address"))?;
            Ok((addr.ip(), *port))
        }
    }
}

fn pair(u: &Option<String>, p: &Option<String>) -> Option<(String, String)> {
    match (u, p) {
        (Some(u), Some(p)) => Some((u.clone(), p.clone())),
        _ => None,
    }
}

pub fn bind_loopback() -> io::Result<TcpListener> {
    let listener = TcpListener::bind(("127.0.0.1", 0))?;
    listener.set_nonblocking(true)?;
    Ok(listener)
}

/// Accept loop; exits when `shutdown` is set. Each connection is handled on its own thread.
pub fn serve(listener: TcpListener, dialer: Dialer, shutdown: Arc<AtomicBool>) {
    while !shutdown.load(Ordering::SeqCst) {
        match listener.accept() {
            Ok((stream, _peer)) => {
                let dialer = dialer.clone();
                let _ = std::thread::Builder::new()
                    .name("gallery-socks5-conn".into())
                    .spawn(move || {
                        if let Err(e) = handle_client(stream, &dialer) {
                            log::debug!("socks5 conn closed: {e}");
                        }
                    });
            }
            Err(ref e) if e.kind() == io::ErrorKind::WouldBlock => {
                std::thread::sleep(Duration::from_millis(50));
            }
            Err(e) => {
                log::warn!("socks5 accept error: {e}");
                break;
            }
        }
    }
}

fn handle_client(mut client: TcpStream, dialer: &Dialer) -> io::Result<()> {
    client.set_nonblocking(false)?;
    client.set_read_timeout(Some(Duration::from_secs(30)))?;

    // Greeting: VER, NMETHODS, METHODS...
    let mut head = [0u8; 2];
    client.read_exact(&mut head)?;
    if head[0] != VER {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "not socks5"));
    }
    let mut methods = vec![0u8; head[1] as usize];
    client.read_exact(&mut methods)?;
    // We only offer "no auth".
    client.write_all(&[VER, 0x00])?;

    // Request: VER, CMD, RSV, ATYP, ADDR, PORT
    let mut req = [0u8; 4];
    client.read_exact(&mut req)?;
    if req[0] != VER {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "bad request ver"));
    }
    if req[1] != CMD_CONNECT {
        reply(&mut client, 0x07)?; // command not supported
        return Err(io::Error::new(io::ErrorKind::Unsupported, "only CONNECT"));
    }
    let target = read_target(&mut client, req[3])?;

    match dialer.dial(&target) {
        Ok(upstream) => {
            reply(&mut client, REP_OK)?;
            // Clear the handshake-phase timeout for the long-lived splice.
            client.set_read_timeout(None).ok();
            splice(Box::new(client), upstream)
        }
        Err(e) => {
            // Map the failure to a SPECIFIC reply code so the client (OkHttp)
            // surfaces "host unreachable" / "connection refused" instead of the
            // opaque "SOCKS server general failure" — and log it at warn: dial
            // failures are exactly what users report, keep them visible.
            log::warn!("socks5: dial {target:?} failed: {e}");
            let _ = reply(&mut client, rep_for(&e));
            Err(e)
        }
    }
}

/// RFC 1928 reply codes for a failed CONNECT — precise beats generic.
fn rep_for(e: &io::Error) -> u8 {
    match e.kind() {
        io::ErrorKind::NotFound => 0x04,           // host unreachable (DNS failed)
        io::ErrorKind::ConnectionRefused => 0x05,  // connection refused
        io::ErrorKind::PermissionDenied => 0x02,   // not allowed by ruleset (auth)
        io::ErrorKind::TimedOut => 0x03,           // network unreachable
        _ => REP_GENERAL_FAIL,
    }
}

fn read_target(client: &mut TcpStream, atyp: u8) -> io::Result<Target> {
    match atyp {
        ATYP_IPV4 => {
            let mut b = [0u8; 4];
            client.read_exact(&mut b)?;
            let port = read_port(client)?;
            Ok(Target::Ip(std::net::IpAddr::from(b), port))
        }
        ATYP_IPV6 => {
            let mut b = [0u8; 16];
            client.read_exact(&mut b)?;
            let port = read_port(client)?;
            Ok(Target::Ip(std::net::IpAddr::from(b), port))
        }
        ATYP_DOMAIN => {
            let mut len = [0u8; 1];
            client.read_exact(&mut len)?;
            let mut name = vec![0u8; len[0] as usize];
            client.read_exact(&mut name)?;
            let host = String::from_utf8(name)
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "bad domain"))?;
            let port = read_port(client)?;
            // A client (e.g. our own by-IP diagnostic) may send an IP literal in
            // the DOMAIN field; treat it as an IP so we don't try to DNS-resolve it.
            match host.parse::<std::net::IpAddr>() {
                Ok(ip) => Ok(Target::Ip(ip, port)),
                Err(_) => Ok(Target::Domain(host, port)),
            }
        }
        _ => Err(io::Error::new(io::ErrorKind::InvalidData, "bad atyp")),
    }
}

fn read_port(client: &mut TcpStream) -> io::Result<u16> {
    let mut p = [0u8; 2];
    client.read_exact(&mut p)?;
    Ok(u16::from_be_bytes(p))
}

/// Reply with a bound address of 0.0.0.0:0 (clients ignore it for CONNECT).
fn reply(client: &mut TcpStream, rep: u8) -> io::Result<()> {
    client.write_all(&[VER, rep, 0x00, ATYP_IPV4, 0, 0, 0, 0, 0, 0])
}

fn dial_direct(target: &Target) -> io::Result<TcpStream> {
    let stream = match target {
        Target::Ip(ip, port) => TcpStream::connect((*ip, *port))?,
        Target::Domain(host, port) => {
            // Direct mode resolves locally; the accelerated modes never reach here.
            let addr = (host.as_str(), *port)
                .to_socket_addrs()?
                .next()
                .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "no address"))?;
            TcpStream::connect(addr)?
        }
    };
    stream.set_nodelay(true).ok();
    Ok(stream)
}

/// Run the SOCKS5 client handshake against an upstream over any duplex stream,
/// forwarding `target` verbatim so DNS resolves at the upstream (remote DNS).
pub fn socks5_client_handshake<S: Read + Write>(
    up: &mut S,
    auth: Option<&(String, String)>,
    target: &Target,
) -> io::Result<()> {
    // Greeting: offer no-auth (0x00) and, if configured, user/pass (0x02).
    if auth.is_some() {
        up.write_all(&[VER, 0x02, 0x00, 0x02])?;
    } else {
        up.write_all(&[VER, 0x01, 0x00])?;
    }
    let mut chosen = [0u8; 2];
    up.read_exact(&mut chosen)?;
    if chosen[0] != VER {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "upstream not socks5"));
    }
    match chosen[1] {
        0x00 => {}
        0x02 => {
            let (u, p) = auth.ok_or_else(|| {
                io::Error::new(io::ErrorKind::PermissionDenied, "upstream wants auth")
            })?;
            // RFC 1929 user/pass auth.
            let mut msg = vec![0x01, u.len() as u8];
            msg.extend_from_slice(u.as_bytes());
            msg.push(p.len() as u8);
            msg.extend_from_slice(p.as_bytes());
            up.write_all(&msg)?;
            let mut st = [0u8; 2];
            up.read_exact(&mut st)?;
            if st[1] != 0x00 {
                return Err(io::Error::new(
                    io::ErrorKind::PermissionDenied,
                    "upstream auth failed",
                ));
            }
        }
        _ => {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "no acceptable auth",
            ))
        }
    }

    // CONNECT, preserving hostname for remote DNS.
    let mut req = vec![VER, CMD_CONNECT, 0x00];
    match target {
        Target::Ip(std::net::IpAddr::V4(ip), port) => {
            req.push(ATYP_IPV4);
            req.extend_from_slice(&ip.octets());
            req.extend_from_slice(&port.to_be_bytes());
        }
        Target::Ip(std::net::IpAddr::V6(ip), port) => {
            req.push(ATYP_IPV6);
            req.extend_from_slice(&ip.octets());
            req.extend_from_slice(&port.to_be_bytes());
        }
        Target::Domain(host, port) => {
            req.push(ATYP_DOMAIN);
            req.push(host.len() as u8);
            req.extend_from_slice(host.as_bytes());
            req.extend_from_slice(&port.to_be_bytes());
        }
    }
    up.write_all(&req)?;

    // Reply: VER REP RSV ATYP BND.ADDR BND.PORT — read and discard the bound addr.
    let mut rhead = [0u8; 4];
    up.read_exact(&mut rhead)?;
    if rhead[1] != REP_OK {
        return Err(io::Error::new(
            io::ErrorKind::ConnectionRefused,
            "upstream CONNECT failed",
        ));
    }
    let skip = match rhead[3] {
        ATYP_IPV4 => 4,
        ATYP_IPV6 => 16,
        ATYP_DOMAIN => {
            let mut l = [0u8; 1];
            up.read_exact(&mut l)?;
            l[0] as usize
        }
        _ => return Err(io::Error::new(io::ErrorKind::InvalidData, "bad upstream atyp")),
    };
    let mut discard = vec![0u8; skip + 2];
    up.read_exact(&mut discard)?;
    Ok(())
}

/// Bidirectional copy between client and upstream until either side closes.
fn splice(client: Box<dyn Conn>, upstream: Box<dyn Conn>) -> io::Result<()> {
    let client_rd = client.try_clone_box()?;
    let upstream_rd = upstream.try_clone_box()?;
    // client -> upstream on a worker thread; upstream -> client on this one.
    let t = std::thread::spawn(move || copy_half(client_rd, upstream));
    copy_half(upstream_rd, client)?;
    let _ = t.join();
    Ok(())
}

fn copy_half(mut from: Box<dyn Conn>, mut to: Box<dyn Conn>) -> io::Result<()> {
    let mut buf = [0u8; 16 * 1024];
    loop {
        let n = match from.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => n,
            Err(ref e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(e),
        };
        to.write_all(&buf[..n])?;
    }
    to.shutdown_write();
    Ok(())
}
