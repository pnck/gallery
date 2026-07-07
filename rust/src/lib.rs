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

use std::sync::atomic::{AtomicBool, AtomicU16, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

uniffi::setup_scaffolding!();

/// Outbound routing for the local SOCKS5 inbound.
#[derive(Debug, Clone, uniffi::Enum)]
pub enum CoreConfig {
    /// Dial targets directly (no acceleration). Baseline / fallback.
    Direct,
    /// Chain every CONNECT to an upstream SOCKS5, preserving the hostname so DNS
    /// resolves at the upstream (remote DNS, Transport Design §4.2).
    SocksUpstream { host: String, port: u16, username: Option<String>, password: Option<String> },
    // WgThenSocks { wg: WgParams, upstream_host: String, upstream_port: u16 } — phase 2 (T-502).
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
    /// For phase 1, "handshake" == listener is up. WG handshake fields arrive in phase 2.
    pub handshake_ok: bool,
    pub local_socks_port: Option<u16>,
    pub rtt_ms: Option<i64>,
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum TransportError {
    #[error("failed to bind local SOCKS5 listener: {msg}")]
    Bind { msg: String },
    #[error("core already running")]
    AlreadyRunning,
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
}

#[uniffi::export]
impl WgCore {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            inner: Mutex::new(CoreInner { running: false, listener_thread: None }),
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

        let dialer = socks::Dialer::from_config(&config);
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
        let running = self.inner.lock().unwrap().running;
        TransportHealth {
            handshake_ok: running,
            local_socks_port: self.local_socks_port(),
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
