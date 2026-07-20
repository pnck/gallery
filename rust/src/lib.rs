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
    /// WG-configured DNS servers (wg-quick `[Interface] DNS`), resolved over the
    /// tunnel in WgOnly mode. Empty → local resolver fallback (leaks DNS).
    pub dns: Vec<String>,
    pub keepalive_secs: u16,
    /// Tunnel MTU; 0 = default (1280, the safe floor for constrained paths).
    pub mtu: u16,
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

/// Derive the base64 public key for a base64 private key (= `wg pubkey`). Returns
/// an empty string if the private key is invalid, so the UI can update live.
#[uniffi::export]
pub fn derive_wireguard_public_key(private_key: String) -> String {
    wg::derive_public_key(&private_key).unwrap_or_default()
}

/// Route `log` records to Android logcat under the `gallery-wg` tag, once.
/// `adb logcat -s gallery-wg` shows the transport core's handshake progress.
fn init_logging() {
    #[cfg(target_os = "android")]
    {
        use std::sync::Once;
        static INIT: Once = Once::new();
        INIT.call_once(|| {
            // Transport is quiet by default (warn+) so logcat stays focused on the app
            // layer (gallery-sync). Throughput is observable via the in-app diagnostics
            // screen's tx/rx, not the log. Flip GALLERY_WG_LOG=debug/info for traces.
            let spec = std::env::var("GALLERY_WG_LOG")
                .ok()
                .filter(|s| !s.is_empty())
                .unwrap_or_else(|| "warn".to_string());
            let filter = env_filter::Builder::new().parse(&spec).build();
            android_logger::init_once(
                android_logger::Config::default()
                    .with_tag("gallery-wg")
                    // BOTH are required: with_max_level sets the log crate's GLOBAL
                    // max (without it android_logger never calls log::set_max_level,
                    // so it stays Off and nothing prints); with_filter refines on top.
                    .with_max_level(log::LevelFilter::Debug)
                    .with_filter(filter),
            );
        });
    }
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
    /// The WG driver thread has exited (shutdown or unexpected death). Kotlin
    /// must treat this as Failed(retryable) even if no handshake ever completed —
    /// otherwise a pre-handshake driver death wedges the state machine forever.
    pub driver_dead: bool,
    pub local_socks_port: Option<u16>,
    /// Unix epoch seconds of the last completed WG handshake (WG modes only).
    pub last_handshake_epoch: Option<i64>,
    /// WireGuard data bytes sent / received through the tunnel (WG modes only).
    pub tx_bytes: Option<u64>,
    pub rx_bytes: Option<u64>,
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
    /// Human-readable summary of the config the core is ACTUALLY running with,
    /// for the config-dump UI — the ground truth for "which interface address /
    /// DNS / endpoint did the core receive".
    config_summary: Option<String>,
}

#[uniffi::export]
impl WgCore {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        init_logging();
        Arc::new(Self {
            inner: Mutex::new(CoreInner {
                running: false,
                listener_thread: None,
                tunnel: None,
                config_summary: None,
            }),
            shutdown: Arc::new(AtomicBool::new(false)),
            port: Arc::new(AtomicU16::new(0)),
            callback: Mutex::new(None),
        })
    }

    /// Build the outbound chain and start the local SOCKS5 inbound.
    /// Returns the bound loopback port (Transport Design §2.1 `start`+`localSocksPort`).
    pub fn start(&self, config: CoreConfig) -> Result<u16, TransportError> {
        {
            let mut inner = self.inner.lock().unwrap();
            if inner.running {
                return Err(TransportError::AlreadyRunning);
            }
            inner.config_summary = Some(summarize_config(&config));
        }
        // Emitted WITHOUT holding `inner`: the callback runs on this thread and any
        // re-entrant call into the core (health(), etc.) would deadlock otherwise.
        self.emit(CoreState::Starting);
        self.shutdown.store(false, Ordering::SeqCst);

        match self.start_inner(config) {
            Ok(port) => {
                self.emit(CoreState::Running);
                Ok(port)
            }
            Err(e) => {
                // Roll back a partially-started core (e.g. tunnel up but listener
                // bind/spawn failed): a leaked WG driver would keep handshaking the
                // peer and race the NEXT start's driver — two tunnels, one session.
                let mut inner = self.inner.lock().unwrap();
                if let Some(t) = inner.tunnel.take() {
                    t.shutdown();
                }
                inner.listener_thread = None;
                inner.running = false;
                self.port.store(0, Ordering::SeqCst);
                drop(inner);
                self.emit(CoreState::Failed);
                Err(e)
            }
        }
    }


    pub fn local_socks_port(&self) -> Option<u16> {
        let p = self.port.load(Ordering::SeqCst);
        if p == 0 { None } else { Some(p) }
    }

    /// Ground-truth summary of the config the core is running with + live health,
    /// for the config-dump UI. Shows the ACTUAL interface address / DNS / endpoint
    /// the core received — so a UI/plumbing mismatch is immediately visible.
    pub fn transport_info(&self) -> String {
        let inner = self.inner.lock().unwrap();
        let mut s = inner
            .config_summary
            .clone()
            .unwrap_or_else(|| "no config (not started)".into());
        if let Some(t) = &inner.tunnel {
            s.push_str(&format!(
                "\nhandshake_ok={} tx_bytes={} rx_bytes={}",
                t.handshake_ok(),
                t.tx_bytes(),
                t.rx_bytes(),
            ));
        }
        if let Some(p) = self.local_socks_port() {
            s.push_str(&format!("\nlocal_socks=127.0.0.1:{p}"));
        }
        s
    }

    pub fn health(&self) -> TransportHealth {
        let inner = self.inner.lock().unwrap();
        // In WgThenSocks mode "handshake_ok" reflects the real WireGuard handshake;
        // in Direct/SocksUpstream it means the local listener is up.
        let (handshake_ok, driver_dead, last_handshake_epoch, tx_bytes, rx_bytes) = match &inner.tunnel {
            Some(t) => (
                t.handshake_ok(),
                t.driver_dead(),
                t.last_handshake_epoch(),
                Some(t.tx_bytes()),
                Some(t.rx_bytes()),
            ),
            None => (inner.running, false, None, None, None),
        };
        TransportHealth {
            handshake_ok,
            driver_dead,
            local_socks_port: self.local_socks_port(),
            last_handshake_epoch,
            tx_bytes,
            rx_bytes,
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
        // Deterministically stop the WG driver (join its thread) BEFORE dropping the
        // handle — an in-flight tunnel connection may still hold an Arc<WgTunnel>, so
        // relying on Drop alone can leave the old driver running and racing the peer.
        if let Some(t) = &inner.tunnel {
            t.shutdown();
        }
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
    fn start_inner(&self, config: CoreConfig) -> Result<u16, TransportError> {
        // WG modes need the live tunnel woven into the dialer; the tunnel-less
        // modes map straight from the config.
        let dialer = match &config {
            CoreConfig::WgOnly { wg } => {
                let tunnel = start_tunnel(wg)?;
                self.inner.lock().unwrap().tunnel = Some(Arc::clone(&tunnel));
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
                self.inner.lock().unwrap().tunnel = Some(Arc::clone(&tunnel));
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

        let shutdown = Arc::clone(&self.shutdown);
        let handle = std::thread::Builder::new()
            .name("gallery-socks5".into())
            .spawn(move || socks::serve(listener, dialer, shutdown))
            .map_err(|e| TransportError::Bind { msg: e.to_string() })?;

        let mut inner = self.inner.lock().unwrap();
        inner.listener_thread = Some(handle);
        inner.running = true;
        self.port.store(bound_port, Ordering::SeqCst);
        Ok(bound_port)
    }
    fn emit(&self, state: CoreState) {
        if let Some(cb) = self.callback.lock().unwrap().as_ref() {
            cb.on_state(state);
        }
    }
}

/// One-line-per-field dump of the config the core received, for the dump UI.
fn summarize_config(config: &CoreConfig) -> String {
    match config {
        CoreConfig::Direct => "mode=Direct".into(),
        CoreConfig::SocksUpstream { host, port, username, .. } => format!(
            "mode=SocksOnly\nupstream={host}:{port}\nauth={}",
            if username.is_some() { "yes" } else { "no" },
        ),
        CoreConfig::WgOnly { wg } => format!("mode=WgOnly\n{}", summarize_wg(wg)),
        CoreConfig::WgThenSocks { wg, upstream_host, upstream_port, .. } => format!(
            "mode=WgThenSocks\n{}\nupstream_socks={upstream_host}:{upstream_port}",
            summarize_wg(wg),
        ),
    }
}

fn summarize_wg(wg: &WgSettings) -> String {
    // Derive OUR public key from the configured private key so it can be checked
    // against the server's peer config; peer_public_key is the server's own key.
    let my_pub = wg::derive_public_key(&wg.private_key).unwrap_or_else(|_| "<invalid private key>".into());
    let addrs = wg.interface_addresses.join(",");
    format!(
        "interface={:?}\ndns={:?}\nendpoint={}\nkeepalive={}\nmy_public_key(derived)={}\npeer_public_key={}\n\
         --- the server MUST have a [Peer] entry for THIS app:\n\
         [Peer] PublicKey={} AllowedIPs must contain {}\n\
         (if you reused the official client's key, they conflict — use a distinct key + tunnel IP)",
        wg.interface_addresses, wg.dns, wg.endpoint, wg.keepalive_secs, my_pub, wg.peer_public_key, my_pub, addrs,
    )
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
        &wg.dns,
        wg.keepalive_secs,
        wg.mtu,
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
