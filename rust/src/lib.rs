//! Tier-1 transport core (Transport Design §2, PRD §8.4).
//!
//! Exposes the design doc's FFI primitives to Kotlin via UniFFI (ADR-0002):
//! `start` / `local_socks_port` / `health` / `stop` / `set_state_callback`.
//!
//! Phase 1 (this file): a local SOCKS5 inbound whose outbound dialer is either
//! Direct or a chain to an upstream SOCKS5 — real, host-testable, and the
//! `SocksOnly` transport mode. Phase 2 swaps the outbound dialer for a
//! boringtun+smoltcp WireGuard tunnel (`WgThenSocks`) without changing this surface.

mod socks;
mod wg;

use std::net::ToSocketAddrs;
use std::sync::atomic::{AtomicBool, AtomicU16, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

use wg::{WgParams, WgTunnel};

uniffi::setup_scaffolding!();

/// Standard WireGuard parameters. Keys are standard WireGuard base64;
/// `interface_addresses` are the tunnel-interior CIDRs (e.g. "10.0.0.2/32").
/// `endpoint` is host:port — a domain is resolved once, directly, at start (the
/// WG endpoint is public and reachable without the tunnel; everything past it
/// rides encrypted).
#[derive(Debug, Clone, uniffi::Record)]
pub struct WgSettings {
    pub private_key: String,
    pub peer_public_key: String,
    pub preshared_key: Option<String>,
    pub endpoint: String,
    pub interface_addresses: Vec<String>,
    pub keepalive_secs: u16,
}

/// Outbound routing for the local SOCKS5 inbound. WireGuard and the upstream
/// SOCKS5 chain are independent (Direct / SOCKS only / WG only / WG + SOCKS).
#[derive(Debug, Clone, uniffi::Enum)]
pub enum CoreConfig {
    /// Dial targets directly (no acceleration). Baseline / fallback.
    Direct,
    /// Chain every CONNECT to an upstream SOCKS5 over the OS network, preserving
    /// the hostname so DNS resolves at the upstream (remote DNS, Transport §4.2).
    SocksUpstream { host: String, port: u16, username: Option<String>, password: Option<String> },
    /// WireGuard tunnel only: dial the target through the tunnel and out the WG
    /// peer. Domains are resolved locally then connected by IP through the tunnel
    /// (no in-tunnel resolver yet — this leaks DNS but tunnels the connection).
    WgOnly { wg: WgSettings },
    /// Primary accelerated path (Transport §2.1): WireGuard tunnel to `endpoint`,
    /// then chain to the in-tunnel upstream SOCKS5 (remote DNS at the LAN exit).
    WgThenSocks {
        wg: WgSettings,
        upstream_host: String,
        upstream_port: u16,
        upstream_username: Option<String>,
        upstream_password: Option<String>,
    },
}

/// A freshly generated WireGuard keypair (base64), for the config UI.
#[derive(Debug, Clone, uniffi::Record)]
pub struct WireguardKeypair {
    pub private_key: String,
    pub public_key: String,
}

/// Generate a WireGuard keypair (equivalent to `wg genkey` / `wg pubkey`). The
/// private key is a random x25519 secret; the public key is derived from it.
#[uniffi::export]
pub fn generate_wireguard_keypair() -> WireguardKeypair {
    wg::generate_keypair()
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CoreState {
    Idle,
    Starting,
    Running,
    Stopped,
    Failed,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct TransportHealth {
    /// In WgThenSocks mode this is the real WireGuard handshake state; in
    /// Direct/SocksUpstream it means the local SOCKS5 listener is up.
    pub handshake_ok: bool,
    pub local_socks_port: Option<u16>,
    /// Unix epoch seconds of the last completed WG handshake (WgThenSocks only).
    pub last_handshake_epoch: Option<i64>,
    pub rtt_ms: Option<i64>,
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum TransportError {
    #[error("failed to bind local SOCKS5 listener: {msg}")]
    Bind { msg: String },
    #[error("core already running")]
    AlreadyRunning,
    #[error("invalid WireGuard config: {msg}")]
    WgConfig { msg: String },
    #[error("failed to start WireGuard tunnel: {msg}")]
    WgStart { msg: String },
    #[error("not yet implemented: {what}")]
    Unimplemented { what: String },
}

/// State change callback marshalled back to Kotlin (Transport Design §8).
#[uniffi::export(callback_interface)]
pub trait StateCallback: Send + Sync {
    fn on_state(&self, state: CoreState);
}

/// The transport core handle. One per app (single tunnel, Transport Design §6.4).
#[derive(uniffi::Object)]
pub struct WgCore {
    inner: Mutex<CoreInner>,
    shutdown: Arc<AtomicBool>,
    port: Arc<AtomicU16>,
    callback: Mutex<Option<Box<dyn StateCallback>>>,
}

struct CoreInner {
    running: bool,
    listener_thread: Option<JoinHandle<()>>,
    /// Present only in WgThenSocks mode; kept alive for the session and consulted
    /// by `health()`. Dropping it stops the WG driver thread.
    tunnel: Option<Arc<WgTunnel>>,
}

#[uniffi::export]
impl WgCore {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            inner: Mutex::new(CoreInner { running: false, listener_thread: None, tunnel: None }),
            shutdown: Arc::new(AtomicBool::new(false)),
            port: Arc::new(AtomicU16::new(0)),
            callback: Mutex::new(None),
        })
    }

    /// Build the outbound chain and start the local SOCKS5 inbound.
    /// Returns the bound loopback port (Transport Design §2.1 `start`+`localSocksPort`).
    pub fn start(&self, config: CoreConfig) -> Result<u16, TransportError> {
        let mut inner = self.inner.lock().unwrap();
        if inner.running {
            return Err(TransportError::AlreadyRunning);
        }
        self.emit(CoreState::Starting);
        self.shutdown.store(false, Ordering::SeqCst);

        // WG modes need the live tunnel woven into the dialer; the tunnel-less
        // modes map straight from the config.
        let dialer = match &config {
            CoreConfig::WgOnly { wg } => {
                let tunnel = start_tunnel(wg)?;
                inner.tunnel = Some(Arc::clone(&tunnel));
                socks::Dialer::WgDirect { tunnel }
            }
            CoreConfig::WgThenSocks {
                wg,
                upstream_host,
                upstream_port,
                upstream_username,
                upstream_password,
            } => {
                let tunnel = start_tunnel(wg)?;
                inner.tunnel = Some(Arc::clone(&tunnel));
                socks::Dialer::WgThenSocks {
                    tunnel,
                    host: upstream_host.clone(),
                    port: *upstream_port,
                    auth: match (upstream_username, upstream_password) {
                        (Some(u), Some(p)) => Some((u.clone(), p.clone())),
                        _ => None,
                    },
                }
            }
            _ => socks::Dialer::from_config(&config),
        };

        let listener = socks::bind_loopback().map_err(|e| TransportError::Bind { msg: e.to_string() })?;
        let bound_port = listener.local_addr().map(|a| a.port()).unwrap_or(0);
        self.port.store(bound_port, Ordering::SeqCst);

        let shutdown = Arc::clone(&self.shutdown);
        let handle = std::thread::Builder::new()
            .name("gallery-socks5".into())
            .spawn(move || socks::serve(listener, dialer, shutdown))
            .map_err(|e| TransportError::Bind { msg: e.to_string() })?;

        inner.listener_thread = Some(handle);
        inner.running = true;
        drop(inner);
        self.emit(CoreState::Running);
        Ok(bound_port)
    }

    pub fn local_socks_port(&self) -> Option<u16> {
        let p = self.port.load(Ordering::SeqCst);
        if p == 0 { None } else { Some(p) }
    }

    pub fn health(&self) -> TransportHealth {
        let inner = self.inner.lock().unwrap();
        // In WgThenSocks mode "handshake_ok" reflects the real WireGuard handshake;
        // in Direct/SocksUpstream it means the local listener is up.
        let (handshake_ok, last_handshake_epoch) = match &inner.tunnel {
            Some(t) => (t.handshake_ok(), t.last_handshake_epoch()),
            None => (inner.running, None),
        };
        TransportHealth {
            handshake_ok,
            local_socks_port: self.local_socks_port(),
            last_handshake_epoch,
            rtt_ms: None,
        }
    }

    pub fn stop(&self) {
        let mut inner = self.inner.lock().unwrap();
        if !inner.running {
            return;
        }
        self.shutdown.store(true, Ordering::SeqCst);
        if let Some(handle) = inner.listener_thread.take() {
            drop(inner); // release lock before joining
            let _ = handle.join();
            inner = self.inner.lock().unwrap();
        }
        inner.running = false;
        // Dropping the tunnel signals its driver thread to shut down.
        inner.tunnel = None;
        self.port.store(0, Ordering::SeqCst);
        drop(inner);
        self.emit(CoreState::Stopped);
    }

    pub fn set_state_callback(&self, callback: Box<dyn StateCallback>) {
        *self.callback.lock().unwrap() = Some(callback);
    }
}

impl WgCore {
    fn emit(&self, state: CoreState) {
        if let Some(cb) = self.callback.lock().unwrap().as_ref() {
            cb.on_state(state);
        }
    }
}

/// Build [`WgParams`] from [`WgSettings`] (resolving the endpoint once, directly)
/// and start the tunnel. The WG endpoint is public and reachable without the
/// tunnel, so this direct DNS lookup does not leak accelerated traffic (§2.1).
fn start_tunnel(wg: &WgSettings) -> Result<Arc<WgTunnel>, TransportError> {
    let resolved = resolve_endpoint(&wg.endpoint).map_err(|msg| TransportError::WgConfig { msg })?;
    let params = WgParams::parse(
        &wg.private_key,
        &wg.peer_public_key,
        wg.preshared_key.as_deref(),
        &resolved,
        &wg.interface_addresses,
        wg.keepalive_secs,
    )
    .map_err(|msg| TransportError::WgConfig { msg })?;
    WgTunnel::start(params)
        .map(Arc::new)
        .map_err(|e| TransportError::WgStart { msg: e.to_string() })
}

/// Resolve a `host:port` endpoint to an `ip:port` literal.
fn resolve_endpoint(endpoint: &str) -> Result<String, String> {
    let mut it = endpoint
        .to_socket_addrs()
        .map_err(|e| format!("cannot resolve endpoint '{endpoint}': {e}"))?;
    match it.next() {
        Some(addr) => Ok(addr.to_string()),
        None => Err(format!("endpoint '{endpoint}' resolved to no addresses")),
    }
}
