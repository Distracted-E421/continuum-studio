# Android Probe Bridge

A reusable control and measurement channel into the Android app, so an agent can
observe and drive it over the network instead of asking the owner to run one-off
manual tests while driving.

Status: **design**. First slice implemented as `ProbeServer` (loopback/tailnet,
token-gated, no new dependencies).

## Why this exists

Six release cycles were spent fixing car audio from second-hand reports and
inference. Each round cost a build, an install, a drive, and a description of what
happened. The information that would have settled it in one shot - what the audio
stack looked like at the moment of failure, and whether the app was even involved -
was obtainable but not collectable.

The lesson is not "ask better questions". It is that **the device needs an address
the agent can talk to**.

## What it is

An HTTP server inside the app process, bound explicitly, gated by a token, that
exposes:

| Endpoint | Purpose |
| --- | --- |
| `GET  /probe/ping` | Liveness plus app version, so a caller knows what it is talking to |
| `GET  /probe/audio` | The `AudioReport` as JSON - routing, volumes, devices, session state |
| `POST /probe/audio/probe` | Play the test tone, return what the player did |
| `GET  /probe/state` | Dialog state: active id, queue depth, connection, playback phase |

Read-only by default. Actions are a separate, explicitly-listed set.

## Design decisions

**No new dependencies.** The app has no HTTP server library and does not need one:
`minSdk 34` gives `com.sun.net.httpserver`-equivalent primitives via
`java.net.ServerSocket`, and the codebase already hand-rolls protocol work
elsewhere (the Synapsix daemon does the same). Adding Netty/Ktor to a diagnostic
feature would be the wrong trade - it is a larger attack surface and a larger APK
for something used during debugging.

**Bind is explicit, never automatic.** The server starts only when enabled in
diagnostics and only on an explicitly configured interface. Default is **loopback
only**, reached with `adb forward` - which is how the owner's wireless ADB will
work when at home. A `0.0.0.0` bind requires the tailnet-only flag *and* a token,
because a phone on a car's hotspot is not a safe network.

**Token required for everything except `ping`.** The token is generated on first
enable, shown on screen, and stored in DataStore. A caller without it gets 401 and
no information. `ping` is unauthenticated so a client can discover the server and
report "up but I need a token" rather than failing mysteriously.

**Read-only and actions are separated.** `GET` endpoints never change state. The
probe endpoint is a `POST` with an explicit body because it makes sound. This
keeps "what is happening" questions safe to answer at any moment, including while
the owner is driving.

**No arbitrary code, ever.** Unlike the Synapsix daemon's `shell` action, there is
no eval/exec surface. The action set is a fixed enumeration; adding one is a code
change. The reason is that this thing listens on a network by design.

## The leverage this buys

- **Isolation without the owner.** Whether Continuum is implicated in a car audio
  fault becomes a query, not a manual uninstall-and-retest.
- **Capture at the moment of failure.** Poll `/probe/audio` while audio is broken,
  rather than reconstructing it afterwards.
- **Repeatable sweeps.** Route changes, volume steps, focus loss, speaker vs
  Bluetooth vs projection - driven in sequence and recorded, instead of one
  observation per drive.
- **Regression checks.** The same sweep runs after each release, so "did I break
  the car again" is answered mechanically.

## What it deliberately does not do

- It is **not on by default** and not started from boot. A dormant service in
  release builds would repeat the media-session mistake in a different form.
- It does not expose other apps' data, screen contents, or storage.
- It is not a general remote-control channel for the phone; the action set is
  audio diagnostics plus dialog state reads.

## Testing path

The server's request routing and auth are pure enough to unit-test without a
socket, and that is where the tests go: token comparison, method/route matching,
and the 401 path. The socket itself is exercised by a loopback test that starts the
server on port 0, reads the bound port, and makes real requests - no device needed,
because this runs in a JVM unit test.

## Open questions for the owner

1. Should the default bind be loopback-only (adb forward) or tailnet? Loopback is
   safer; tailnet is more convenient from a phone on the road.
2. Should the token be per-session (regenerated each enable) or persistent? Per
   session is safer, persistent is fewer steps.
3. Worth exposing a screenshot/capture endpoint for visual state, or is audio plus
   dialog state enough?
