# Head Unit Visibility

What the owner actually asked for, beyond our own app's audio state:

> "I'd really like to see what is actually accessible on the head unit, that is
> external or internal to how the Android Auto actually functions, and having you be
> able to actually see / touch it."

This is a harder and more interesting question than "why is our audio silent", and
it deserves an honest account of what is and is not reachable from a phone.

## The layers, from our side outward

| Layer | What it is | Reachable from the phone? |
| --- | --- | --- |
| Our app's audio state | `AudioManager`, our engines | **Fully** - already done (`/probe/audio`) |
| Platform audio routing | `AudioManager.getDevices`, `dumpsys audio`, audio policy | **Mostly** - `dumpsys` via Shizuku; some needs root |
| Android Auto app | What AA itself decides, its own logs | **Partially** - `dumpsys`, logcat, AA debug screens |
| Projection session | The USB/AOA transport, what the phone presents | **Indirectly** - visible as `remote-submix`, ADB `dumpsys usb` |
| The head unit | Subaru's own firmware, its source selection, volume state | **No** - it is a separate computer on the far end of USB |

The last line is the crux and it should be said plainly: **the head unit is not
addressable from the phone.** It is a separate machine. Android Auto is a
*projection protocol* - the phone renders, the car displays and plays. There is no
API by which an app on the phone reads or sets the car's source selection or volume.

That means "see what is accessible on the head unit" has to be answered by
observing what the *phone* knows about the session, which is a real and useful thing
even though it is one layer removed.

## What is genuinely obtainable, and how

### 1. `dumpsys` over Shizuku (already available)

The app already has a Shizuku bridge that runs commands as UID 2000. That unlocks a
large amount of ground truth with no new permissions:

- `dumpsys audio` - every stream, its volume index, active focus stack, the full
  list of routes and device addresses. This is the authoritative answer to "who
  holds audio focus" and it is what we have been inferring instead.
- `dumpsys media_session` - every live session, its state, and **which app is the
  active one** on the car's channel. This directly answers "is Continuum holding
  the head unit".
- `dumpsys usb` - when AA is over USB, the AOA accessory handshake.
- `dumpsys car_service` / `cmd car_service` - on devices with the car service.
- `dumpsys activity services` - whether another app holds a foreground service that
  could be grabbing audio.

### 2. Logcat stream, filtered (`media_session`, `AudioService`, `Car`, `AA`)

An in-app logcat reader is a normal thing to build with `Runtime.exec`. Reading
Android Auto's own logs would show the session negotiation, which is exactly the
"internal to how AA functions" part of the question. Tag filters keep it small.

Note: this is a diagnostics surface that reads other apps' log lines, so it should be
scoped to specific tags and not a general log firehose.

### 3. The AA debug screens

Android Auto has developer screens (day/night, resolution, connection quality,
"Developer settings" in the AA phone app). Some are readable through AA's own
content providers. This is undocumented territory and would need empirical work on
the device.

### 4. What cannot be done, and should not be attempted

- Reading the head unit's firmware, source selection, or volume: not addressable.
- Injecting input to the head unit: not addressable.
- Anything requiring root: out of scope; the owner's phone is unrooted and Shizuku
  gives shell-level access only.

## The honest conclusion about the car

Given the above, the earlier six rounds of "fix the app, drive, report back" were the
wrong shape of effort. If the fault is the head unit not entering a media state, no
app change can fix it, and no amount of app-side instrumentation would have shown
that either - what would have shown it is `dumpsys media_session` and
`dumpsys audio` **while the fault is live**, which is exactly what this bridge is for.

## Proposed next slices

1. **`/probe/dumpsys?what=audio|media_session|usb`** - run the command through the
   existing Shizuku bridge, return the text. Highest value per line of code, and it
   answers "who holds the channel" directly.
2. **`/probe/logcat?tags=...`** - bounded, filtered, ring-buffered.
3. **A recorded incident file** - the app writes `/probe/audio` + `dumpsys` output to
   a timestamped file whenever audio changes state or a dialog is read, so the moment
   of failure is captured without anyone watching.

Slice 3 is the one that fixes the *testing* problem rather than any specific bug: it
means the next car fault arrives with evidence attached, rather than as a
description.

## Scoping note from the owner

> "If we do end up doing 2, what I may have it instead is the scoped as a separate
> app that can be toggled on in a different lane."

Agreed, and recorded: the network-exposed listener is the piece that should probably
live in its own app rather than ship inside Continuum. Continuum would write the
files and expose only the loopback surface; a separate "probe" app could hold the
tailnet listener, be installed only when wanted, and be uninstalled when not. That
keeps the attack surface out of the app the owner actually drives with.
