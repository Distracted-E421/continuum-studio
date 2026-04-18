# ui-iced — Agent notes

Rust + **iced 0.14** desktop UI for Continuum Studio. Broader project context lives in [`../AGENTS.md`](../AGENTS.md).

## Toolchain checks

```bash
cd ui-iced
cargo fmt
cargo clippy --all-targets --all-features -- -D warnings
cargo test
```

Use `nix develop` at the repo root if system libraries (Wayland, etc.) are missing.

## April 18, 2026 (overnight AFK session)

- `cargo clippy --all-features -- -D warnings` and `cargo clippy --all-targets --all-features -- -D warnings`: clean.
- `cargo test`: **139** unit tests, all passing.
- Removed unused `build_preview_section` in `cli_agents.rs` (superseded by `build_preview_section_with_tokens`; no call sites).
- Intentional `#[allow(dead_code)]` remains elsewhere (WS serde fields, planned `CoreRequest` variants, updater helpers, etc.) — see root `AGENTS.md` desktop section.
