# Continuum Studio Agent Memory

## Project Context

AI Orchestration Platform for multi-agent control, monitoring, and verification.

**See `ecosystem.md` for:** dev preferences, language philosophy, Synapsix tooling.

**License:** SSPL-1.0

## Architecture

```
ui-iced/     # Desktop UI (Rust + iced 0.14, COSMIC styling)
android/     # Mobile app (Kotlin + Jetpack Compose)
core/        # Orchestration hub (Elixir/OTP)
crates/      # Shared Rust libraries
```

## Status

**Branch:** `iced-migration` (clean, 139 tests pass)

### Desktop UI (`ui-iced/`)

| Feature | Module |
|---------|--------|
| CLI Agents | presets, batch launch, dialog inbox |
| Orchestrator | decision engine, mode control, triage queue |
| Diagrams | Mermaid/D2 via `widgets/diagram.rs` |
| Offline mode | operation queue via `offline.rs` |
| Activity Feed | real-time events, filters |
| Task Queue | HTTP/WebSocket to Synapsix |

### Android (`android/`)

- **XR middleware overlay** for Samsung S23 Ultra + RayNeo 3 glasses (evolving beyond Dex/mirror)
- `ZonedGlassesLayout`: left=dialog content, center=see-through, right=options; entrance animations (v1.9.0+)
- `DialogControlPanel`: phone becomes input device in XR mode (numbered options, fixed keyboard, voice typing)
- **Agent Camera**: Capture images from phone camera and send directly to agents (Settings → Agent Camera)
  - `camera/AgentCameraClient.kt`: HTTP client for Synapsix/Cognix visual context API
  - `ui/camera/AgentCameraScreen.kt`: CameraX preview + capture + send flow
  - Connection indicators for both Synapsix and Cognix backends
- Widget Bay dashboard, Dialog Client (WS :8080)
- Multi-endpoint config with auto-fallback
- **OTA updates** via GitHub releases (`gh release create`); app checks releases for APK download
- Android Auto integration planned (`docs/ANDROID_AUTO_INTEGRATION_PLAN.md`)

### Core (`core/`)

- Elixir/OTP orchestration hub
- Agent Bridge (multi-provider AI)
- Version Registry, Workspace Tracker

## Synapsix Integration

| Component | Purpose |
|-----------|---------|
| `dialog_client.rs` | D-Bus to synapsix-dialog-daemon |
| `cli_agents_client.rs` | HTTP/WS to Synapsix API |
| `decision_engine.rs` | Auto-handling by orchestrator mode |
| `orchestrator_panel.rs` | Mode control, triage UI |

**D-Bus:** `sh.synapsix.Dialog` + `sh.synapsix.TerminalMonitor`

**Orchestrator Modes:** UserActive, UserDelegate, Spectator, Autonomous

## Build & Test

```bash
# Desktop
cd ui-iced && cargo build --release

# Android  
cd android && ./gradlew assembleDebug

# Core
cd core && mix deps.get && mix compile
```

## Integration Points

- **Synapsix Dialog:** WebSocket port 8080
- **Terminal Monitor:** Cursor terminal insights
- **Phosphor:** Screen capture via D-Bus
- **NeSy:** Verification bridge (Z3/SMT)

## Related Projects

- `~/synapsix` - Dialog daemon, terminal monitor, harnesses
- `~/phosphor` - Screen capture service

## Learned Workspace Facts

- Primary Continuum Studio Android deploy target is Samsung Galaxy S24 (Sep 2026; replaced S23 Ultra with broken display)

## Android dialog + audio pipeline (2026-11-17)

Hard-won knowledge from a full overhaul of the dialog page and speech pipeline.

### Build

- JDK is not on PATH: `export JAVA_HOME=/nix/store/1slz0vmmgsd7p6i4c0jaywkpw56wl2yg-openjdk-21.0.12+8` and put `$JAVA_HOME/bin` first. SDK is at `~/Android/Sdk` (`local.properties`).
- `./gradlew --offline` works for `compileDebugKotlin`, `testDebugUnitTest`, `assembleDebug`. JUnit is not in the offline cache; the first test run needs network.
- Unit tests are plain JUnit in `app/src/test`. 80 of them, all pure (no Robolectric).

### Release / OTA

- Releases are **debug-signed** (no signing config in `build.gradle.kts`), so installs are interchangeable but cannot be published to Play.
- In-app OTA reads **GitHub** (`OtaUpdateManager`). The Forgejo API 404s for this repo, so the Gitea fallback never fires — GitHub is the only working path.
- Asset must be named `continuum-<versionName>.apk`; versionCode must equal `major*10000+minor*100+patch` or every check reports an update.
- Verify a release really contains the change by unzipping and grepping the dex (`unzip -p x.apk 'classes*.dex' | strings | grep -c <Symbol>`). A build log saying success is not proof; one release shipped a round behind.

### Audio pipeline invariants

- `AudioHub` owns **one** `DialogAudioPlayer`; `FallbackTts` owns **one** `TextToSpeech`. `DialogTTSHelper` is constructed by three surfaces (ViewModel, notification service, AutoReplyService) — never give it per-instance engines. A prior commit claimed one TTS engine while three existed.
- Three OkHttp clients exist on purpose: WebSocket (`readTimeout(0)`, correct for a quiet socket), requests (15/20/20), uploads (20/120/120). Do not make HTTP calls on the WebSocket client — that inherited an infinite timeout and answers could hang forever.
- `DialogAudioPlayer.playScript` is **producer/consumer with `PREFETCH_AHEAD = 2`**, so synthesis overlaps playback. A sequential generate→play→wait loop (the original design) put a full generation stall at every segment boundary; measured 1.6s generate vs 3.7s play made that audible as choppiness. **This must never go back to zero lookahead.**
- **"Not playing" is not "finished".** `awaitSegmentFinish` requires the position to reach the clip duration or the session to report ENDED, with `START_GRACE_MS`/`LOAD_GRACE_MS` windows and `MAX_SEGMENT_PLAY_MS` as a ceiling. A player reports not-playing while starting, while buffering, and while a media session loads the next item — treating any of those as completion truncated segments mid-word and started the next over the top. This was reported by the user as "jumps over itself, cutting each section off" and is the single most user-visible bug found.
- Synthesis is cancelled via an `invokeOnCompletion` hook on the Job; without it, stop/skip left OkHttp calls running, burning quota and caching unheard audio.
- Clips contain **no** leading/trailing silence (verified with silencedetect), so the inter-segment `delay()` is the *entire* pause heard. Pauses are 120-320ms; the original 240-500ms read as 12.6% silence and sounded choppy.
- Sentences are **chunked to ~180 chars** (`CHUNK_TARGET_CHARS`), not one segment per sentence: per-sentence segments meant one HTTP request each (12 for a typical dialog). List items join with ". " so grouping does not create a run-on. Long single sentences split at word boundaries (`CHUNK_MAX_CHARS` 320).

### EasyVoice API (measured against the live service)

- **It does not honour SSML.** `<break time="3s"/>` returns 3.0s of the word being spoken; `<speak>` wrappers make clips *longer*. `z0` empty input is HTTP 400. Pauses must be silence between segments.
- Text-level levers that work: punctuation (a full stop ≈ +0.3s), blank lines (≈ +1.15s), and `speed`. Ellipses and em-dashes do nothing.
- Voices verified working: `af_heart`, `af_bella`, `af_nicole`, `am_fenrir`, `am_michael`, `bf_emma`, `bm_fable`.
- Audio is cached on disk keyed by `v<CACHE_SCHEMA_VERSION>|voice|speed|spokenText`. **Bump `CACHE_SCHEMA_VERSION` whenever the dictionary or compiler changes**, or the fix is inaudible for anything already generated.

### Judging pronunciation without a device

- `tools/check-pronunciation.sh` synthesizes a phrase, transcribes it with `whisper-cli`, and compares against the plain spelling. Needs `WHISPER_CLI` (nix: `whisper-cpp`) and `~/.cache/whisper/ggml-base.en.bin`.
- Whisper is a language model and **cannot spell invented names**: "Synapsix" always transcribes as "Synapse 6" whatever the audio says. Use the controls ("refrigerator", "obsidian" transcribe exactly) to know the tool is working, and judge project names by *relative* signals: does the respelled clip differ and get longer?
- Entries verified this way: `continuum`→"con-tin-yoo-um", `nixos`→"nicks-oh-ess", `phosphor`→"foss for", `forgejo`→"forge joe" (plain "Forgejo" gave "ForZia"/"Furtier").

### Daemon contracts (checked against a live daemon on :8080)

- `/api/answer` returns **HTTP 200 with `success:false`** for logical failures, so HTTP status says nothing about retryability. Stale-dialog messages: "No active dialog", "Dialog not found or not active", "Dialog not found or already responded" — all mean "gone", not "failed" (`String.isStaleDialogError()`).
- History uses `answered_at` (not `completed_at`); the app read the wrong name and silently showed no timestamps. `options` is never sent.
- WebSocket frames are a flat object tagged `type`: `initial`, `NewDialog`, `DialogCompleted`, `QueueUpdate`, `HoldModeChanged`. There is **no HTTP POST that creates dialogs** — creation goes over D-Bus, so you cannot trigger one with curl.
- `DialogContractTest` pins these using payloads captured verbatim from the daemon.

### State rules that must not regress

- `DialogStateMerge.onFetched` clears draft input only when the dialog *identity* changes. Otherwise a comment typed for one dialog gets submitted against the next.
- `readDialogIds` in `DialogViewModel` survives a null `activeDialog`, so a reconnect does not restart a read from the top.
- Answering stops playback immediately rather than waiting for server confirmation.

### Dialog audio ordering (owner decision)

- Prompt first, then options, even though a real dialog is ~21s and options start ~17s in. Do not reorder. The escape hatch is the "Options" button in the audio strip (`skipToOptions`), which restarts at the first OPTION segment without synthesizing the skipped ones.

### Audio focus: OVERLAP is the owner's decision (2026-11-18)

**Owner requirement: dialog audio must not break playback in another app** (Vivaldi named
explicitly). The chosen behaviour is **OVERLAP** - request *no* audio focus at all, so the other
app is never told the dialog started and cannot be left paused, ducked or broken. `DUCK` and
`PAUSE` exist in `FocusPolicy` but are opt-in with their cost stated in the UI; overlapped is the
fail-safe default and what an unreadable preference parses to.

Three real bugs were behind "audio breaks other apps", all now fixed and pinned by
`FocusPolicyTest` (9 tests) + `AudioPrefsKeysTest`:

- **The two engines disagreed.** Local `MediaPlayer` asked `GAIN_TRANSIENT_MAY_DUCK` for
  `FocusPolicy.DUCK`/`PAUSE`; `DialogMediaService`'s ExoPlayer was hardcoded
  `handleAudioFocus = false`, so the car path played at full volume over music while the phone
  path ducked. Both now derive from `FocusPolicy` (`DialogMediaService.setHandleAudioFocus`,
  reachable via `MediaSessionBridge.applyFocusPolicy`).
- **`hasFocus` was never reset on loss**, so after any loss every later segment skipped
  `requestFocus()` and played unfocused - a silent mid-dialog degradation with no log line.
- **Every loss called `pause()`**, the same call as the user's own pause, so one nav prompt ended
  the read permanently. Loss is now a *hold* (`heldByFocusLoss`, resumed on GAIN); a user pause is
  never auto-undone.

Under OVERLAP `FocusResponder` returns `IGNORE` for every change - acting on an event we never
participated in would be the bug.

**Do not reintroduce blanket appop writes.** `clearFocusOverrides` used to write `default` to six
packages including Vivaldi, clobbering whatever Sound Assistant had set. Prior modes are now
recorded in SharedPreferences (`multisound_prior_modes`) before the first change and restored
exactly; an `UNSET` op is `cmd appops reset`, not set to default. `applyCarProfile` defaults to
`DIALOG_COMPETITORS` (Spotify, Audible) rather than every media app, and the UI labels Vivaldi as
deliberately untouched.

**Three engines, three attribute sites** - keep them all `USAGE_MEDIA` +
`CONTENT_TYPE_SPEECH`: `DialogAudioPlayer.playFileLocally`, `DialogMediaService.audioAttributes()`,
and `FallbackTts` (which had *none*, so the on-device voice used the TextToSpeech default of
`USAGE_ASSISTANT` - the channel AA closes when STT ends, i.e. inaudible exactly when the fallback
is needed).

`AudioDiagnosticsScreen`'s `requestAudioFocus(null, stream, gain)` probe returned FAILED on modern
Android (null listener), reporting "not grantable" on healthy devices. Use a real
`AudioFocusRequest` and abandon it, as it now does.

**Not verified on a device.** No wireless ADB to the S24 was available; everything above is
verified by compile + unit test + dex inspection (`strings` on `classes*.dex` for
`audio_focus_policy`, `DIALOG_COMPETITORS`, `multisound_prior_modes`, `HOLD_FOR_FOCUS`). Whether
Samsung's framework MultiSound setting is *also* required for true simultaneous mixing is
unverified - OVERLAP plus `multi_audio_focus_enabled` is the intended combination.
