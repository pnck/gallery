//! Minimal SOCKS5 inbound + outbound dialer (RFC 1928), std-only.
//!
//! Inbound: no-auth SOCKS5 accepting CONNECT to IPv4 / IPv6 / domain targets.
//! Outbound: Direct dial, or chain to an upstream SOCKS5 preserving the hostname
//! so DNS resolves at the far end (remote DNS, Transport Design §4.2).

use crate::CoreConfig;
use std::io::{self, Read, Write};
use std::net::{TcpListener, TcpStream, ToSocketAddrs};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

const VER: u8 = 0x05;
const CMD_CONNECT: u8 = 0x01;
const ATYP_IPV4: u8 = 0x01;
const ATYP_DOMAIN: u8 = 0x03;
const ATYP_IPV6: u8 = 0x04;
const REP_OK: u8 = 0x00;
const REP_GENERAL_FAIL: u8 = 0x01;

/// Target address, kept as sent (domains are NOT resolved locally).
#[derive(Clone, Debug)]
enum Target {
    Ip(std::net::IpAddr, u16),
    Domain(String, u16),
}

#[derive(Clone)]
pub enum Dialer {
    Direct,
    Socks { host: String, port: u16, auth: Option<(String, String)> },
}

impl Dialer {
    pub fn from_config(config: &CoreConfig) -> Dialer {
        match config {
            CoreConfig::Direct => Dialer::Direct,
            CoreConfig::SocksUpstream { host, port, username, password } => Dialer::Socks {
                host: host.clone(),
                port: *port,
                auth: match (username, password) {
                    (Some(u), Some(p)) => Some((u.clone(), p.clone())),
                    _ => None,
                },
            },
        }
    }

    fn dial(&self, target: &Target) -> io::Result<TcpStream> {
        match self {
            Dialer::Direct => dial_direct(target),
            Dialer::Socks { host, port, auth } => dial_via_socks(host, *port, auth.as_ref(), target),
        }
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
            splice(client, upstream)
        }
        Err(e) => {
            let _ = reply(&mut client, REP_GENERAL_FAIL);
            Err(e)
        }
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
            Ok(Target::Domain(host, port))
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

/// Chain to an upstream SOCKS5, forwarding the target verbatim (remote DNS).
fn dial_via_socks(
    host: &str,
    port: u16,
    auth: Option<&(String, String)>,
    target: &Target,
) -> io::Result<TcpStream> {
    let mut up = TcpStream::connect((host, port))?;
    up.set_nodelay(true).ok();
    up.set_read_timeout(Some(Duration::from_secs(30)))?;

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
                return Err(io::Error::new(io::ErrorKind::PermissionDenied, "upstream auth failed"));
            }
        }
        _ => return Err(io::Error::new(io::ErrorKind::PermissionDenied, "no acceptable auth")),
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
        return Err(io::Error::new(io::ErrorKind::ConnectionRefused, "upstream CONNECT failed"));
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
    Ok(up)
}

/// Bidirectional copy between client and upstream until either side closes.
fn splice(client: TcpStream, upstream: TcpStream) -> io::Result<()> {
    let c2 = client.try_clone()?;
    let u2 = upstream.try_clone()?;
    let t = std::thread::spawn(move || copy_half(client, upstream));
    copy_half(u2, c2)?;
    let _ = t.join();
    Ok(())
}

fn copy_half(mut from: TcpStream, mut to: TcpStream) -> io::Result<()> {
    from.set_read_timeout(None).ok();
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
    let _ = to.shutdown(std::net::Shutdown::Write);
    Ok(())
}
