//! Test Harness Server
//!
//! Run this to start a mock Studio Core server for UI testing.
//!
//! # Usage
//!
//! ```bash
//! cargo run --bin test-harness
//! # Then in another terminal:
//! cargo run --bin continuum-studio
//! ```

use anyhow::Result;
use continuum_studio_ui::test_harness::run_standalone;
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> Result<()> {
    // Initialize logging
    let filter = EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| EnvFilter::new("info"));

    tracing_subscriber::fmt()
        .with_env_filter(filter)
        .with_target(false)
        .init();

    println!("╔══════════════════════════════════════════════════════════╗");
    println!("║       Continuum Studio Test Harness                     ║");
    println!("╠══════════════════════════════════════════════════════════╣");
    println!("║ Socket: /tmp/continuum-studio-test.sock                 ║");
    println!("║ Auto-events: enabled (2s interval)                      ║");
    println!("║                                                         ║");
    println!("║ Press Ctrl+C to stop                                    ║");
    println!("╚══════════════════════════════════════════════════════════╝");
    println!();

    run_standalone().await
}

