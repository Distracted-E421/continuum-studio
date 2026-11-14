# synapsix-aa-video

Experimental app for proving we can draw our own frames onto an Android Auto
head unit.

Separate app (`com.datapunk.aa.video`) by design, so that:

- its NAVIGATION category doesn't have to justify itself inside Continuum
- installer-spoofing experiments never touch the real app
- breaking it cannot break dialogs or audio

## Why video is possible at all

Android Auto's UI *is already an H.264 stream*: the phone renders into an
offscreen VirtualDisplay, encodes it, and sends NAL units over USB. The head
unit is a decoder with a touchscreen. Drawing extra frames adds no protocol
cost.

The supported escape hatch is `SurfaceCallback`. Declare the NAVIGATION
category, request `NAVIGATION_TEMPLATES` + `ACCESS_SURFACE`, then draw anything
into the Surface the host provides.

**Verified against the Car App release notes:** versions 1.8.0 / 1.9.0-alpha add
media chrome only (`MediaPlaybackStyle`, `Action.TYPE_MEDIA_PLAYBACK`,
`ACTION_MEDIA_SHOW_PLAYBACK_VIEW`, Minimized Control Panel). There is **no video
API** in 1.8 or 1.9. Surface access remains NAVIGATION / MapWithContent only. The
beta does not unlock anything we don't already have.

## What it does today

`VideoScreen` registers a `SurfaceCallback`, takes ownership of the Surface, and
runs a 30fps draw thread painting a pulsing circle + "Surface OK".

That is deliberately minimal: getting a *moving* image on the head unit proves
the acquisition path, which is the only genuinely uncertain part. Swapping
Canvas for ExoPlayer writing directly into the Surface is then a local change.

## Build

```bash
export JAVA_HOME=/nix/store/1slz0vmmgsd7p6i4c0jaywkpw56wl2yg-openjdk-21.0.12+8
export PATH=$JAVA_HOME/bin:$PATH
cd ~/dev/core/continuum-studio/android
./gradlew :aa-video:assembleDebug
```

Output: `aa-video/build/outputs/apk/debug/aa-video-debug.apk`

## Install — the installer spoof matters

Templated car apps are refused on **physical** head units when sideloaded, even
with "Unknown sources" enabled, because Android Auto checks
`PackageManager.getInstallSourceInfo()`. A plain `adb install` works on the
Desktop Head Unit but the app will not appear in the car.

```bash
adb push aa-video-debug.apk /data/local/tmp/
adb shell pm install -i "com.android.vending" -r /data/local/tmp/aa-video-debug.apk
```

Verified on this device that `pm install-create -i com.android.vending` is
accepted by shell (UID 2000), so this path is available. Whether the projection
host is satisfied by the spoof alone is the open question this app exists to
answer.

## Test on the Desktop Head Unit first

DHU is installed at `~/Android/Sdk/extras/google/auto/desktop-head-unit`.

1. Phone: Android Auto → overflow menu → **Start head unit server**
2. `adb forward tcp:5277 tcp:5277`
3. `steam-run ~/Android/Sdk/extras/google/auto/desktop-head-unit --usb`
   (`--usb` may complain in accessory mode; plain `desktop-head-unit` over the
   forwarded port also works)

DHU does **not** enforce the installer check, so this confirms the surface path
in isolation before involving the car.

## Host validation

`VideoCarAppService.createHostValidator()` returns
`ALLOW_ALL_HOSTS_VALIDATOR` for debuggable builds only, because the DHU is not a
production host signature. Release builds use the real allowlist. Do not ship a
release build that accepts all hosts.

## Verified in the APK

```
uses-permission: androidx.car.app.NAVIGATION_TEMPLATES
uses-permission: androidx.car.app.ACCESS_SURFACE
service com.datapunk.aa.video.VideoCarAppService
  action androidx.car.app.CarAppService
  category androidx.car.app.category.NAVIGATION
```

## Next steps

1. Run on DHU — confirm the circle animates.
2. Install to the car with the installer spoof — confirm it appears at all.
3. If it appears, replace the Canvas loop with `ExoPlayer.setVideoSurface()`.
4. Only then consider YouTube / camera feeds.
