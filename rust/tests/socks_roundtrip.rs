//! Host-side end-to-end test of the SOCKS5 inbound in Direct mode:
//! start the core, connect through its loopback SOCKS5 to a local echo server,
//! and verify bytes round-trip. Validates the FFI-exposed core without a device.

use gallery_transport::{CoreConfig, WgCore};
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};

fn spawn_echo() -> u16 {
    let listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
    let port = listener.local_addr().unwrap().port();
    std::thread::spawn(move || {
        for stream in listener.incoming() {
            let mut s = stream.unwrap();
            std::thread::spawn(move || {
                let mut buf = [0u8; 1024];
                loop {
                    match s.read(&mut buf) {
                        Ok(0) | Err(_) => break,
                        Ok(n) => {
                            if s.write_all(&buf[..n]).is_err() {
                                break;
                            }
                        }
                    }
                }
            });
        }
    });
    port
}

/// Perform a no-auth SOCKS5 CONNECT to 127.0.0.1:target_port over `proxy`.
fn socks5_connect(proxy_port: u16, target_port: u16) -> TcpStream {
    let mut s = TcpStream::connect(("127.0.0.1", proxy_port)).unwrap();
    // greeting: no-auth
    s.write_all(&[0x05, 0x01, 0x00]).unwrap();
    let mut resp = [0u8; 2];
    s.read_exact(&mut resp).unwrap();
    assert_eq!(resp, [0x05, 0x00]);
    // CONNECT to 127.0.0.1:target_port (IPv4)
    let mut req = vec![0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1];
    req.extend_from_slice(&target_port.to_be_bytes());
    s.write_all(&req).unwrap();
    let mut rep = [0u8; 10];
    s.read_exact(&mut rep).unwrap();
    assert_eq!(rep[1], 0x00, "SOCKS5 CONNECT should succeed");
    s
}

#[test]
fn direct_mode_round_trips_through_local_socks() {
    let echo_port = spawn_echo();
    let core = WgCore::new();
    let proxy_port = core.start(CoreConfig::Direct).unwrap();
    assert!(proxy_port > 0);
    assert_eq!(core.local_socks_port(), Some(proxy_port));
    assert!(core.health().handshake_ok);

    let mut conn = socks5_connect(proxy_port, echo_port);
    conn.write_all(b"hello byos").unwrap();
    let mut buf = [0u8; 10];
    conn.read_exact(&mut buf).unwrap();
    assert_eq!(&buf, b"hello byos");

    core.stop();
    assert_eq!(core.local_socks_port(), None);
    assert!(!core.health().handshake_ok);
}
