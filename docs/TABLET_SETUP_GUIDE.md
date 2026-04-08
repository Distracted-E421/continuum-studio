# T90 Tablet Setup Guide

## Quick Wins Checklist

This guide covers improving your T90 tablet experience with FOSS apps and optional ADB debloating.

### Prerequisites

- [ ] Tablet charged >20%
- [ ] WiFi connected
- [ ] USB cable for ADB (optional but recommended)

---

## Part 1: F-Droid App Installations (No Root/USB Required)

### Step 1: Install F-Droid

1. On tablet, open browser
2. Go to `https://f-droid.org/`
3. Download and install F-Droid APK
4. Allow "Install from unknown sources" when prompted

### Step 2: Install Kvaesitso Launcher

```
Package: de.mm20.launcher2.release
```

1. Open F-Droid
2. Search "Kvaesitso"
3. Install
4. Set as default launcher when prompted (or via Settings → Apps → Default apps)

**Why Kvaesitso:**
- Search-centric UI that works well on tablets
- GPL licensed, privacy-respecting
- Flexible widget support
- Good large-screen layouts

### Step 3: Install Taskbar

```
Package: com.farmerbb.taskbar
```

1. Open F-Droid
2. Search "Taskbar"
3. Install
4. Grant overlay permission when prompted

**Why Taskbar:**
- PC-style start menu at bottom
- Freeform windows support (Android 7+)
- Desktop mode on Android 10+ with external display
- Perfect for tablet productivity

### Step 4: Install NotiFilter (Optional)

```
Package: co.adityarajput.notifilter
```

Helps manage notification spam from bloatware you can't uninstall.

---

## Part 2: Developer Options Setup

### Enable Developer Options

1. Settings → About tablet
2. Find "Build number"
3. Tap it 7 times
4. Enter PIN/password if prompted
5. "Developer mode enabled" toast appears

### Recommended Developer Settings

```
Settings → System → Developer options
```

| Setting | Recommended Value | Why |
|---------|-------------------|-----|
| Window animation scale | 0.5x | Feels snappier |
| Transition animation scale | 0.5x | Feels snappier |
| Animator duration scale | 0.5x | Feels snappier |
| USB debugging | ON | For ADB debloat |
| Stay awake | Your choice | Useful while charging at desk |

---

## Part 3: ADB Debloating (Requires USB + PC)

### Prerequisites

- USB cable connected to NixOS machine
- USB debugging enabled on tablet
- Tablet unlocked and screen on

### Step 1: Verify ADB Connection

On your NixOS machine (Obsidian):

```bash
# Check if tablet is detected
adb devices

# Should show something like:
# List of devices attached
# XXXXXXXX device
```

If it shows "unauthorized", check tablet for USB debugging prompt and approve.

### Step 2: List Installed Packages

```bash
# Save package list for reference
adb shell pm list packages > ~/tablet-packages.txt

# View only third-party apps
adb shell pm list packages -3

# View only system apps
adb shell pm list packages -s
```

### Step 3: Identify Bloatware

Common bloatware package patterns to look for:
- `com.facebook.*` - Facebook suite
- `com.google.android.apps.tachyon` - Google Duo
- `com.google.android.videos` - Google Play Movies
- `com.amazon.*` - Amazon apps
- `com.netflix.*` - Netflix
- Any vendor-specific junk (varies by manufacturer)

### Step 4: Disable (Not Uninstall) Bloatware

**IMPORTANT: Use DISABLE, not uninstall, for system apps!**

```bash
# Disable a package (reversible)
adb shell pm disable-user --user 0 com.example.bloatware

# Example: Disable Facebook suite
adb shell pm disable-user --user 0 com.facebook.katana
adb shell pm disable-user --user 0 com.facebook.orca
adb shell pm disable-user --user 0 com.facebook.appmanager

# Example: Disable Google apps you don't use
adb shell pm disable-user --user 0 com.google.android.apps.tachyon
adb shell pm disable-user --user 0 com.google.android.videos
```

### Step 5: Re-enable if Needed

```bash
# Re-enable a package
adb shell pm enable com.example.bloatware
```

### Step 6: Reboot and Test

```bash
adb reboot
```

After reboot, verify:
- [ ] WiFi works
- [ ] Settings app works
- [ ] Camera works
- [ ] Play Store works (if you need it)

---

## Part 4: Taskbar Configuration for Desktop Feel

### Initial Setup

1. Open Taskbar app
2. Grant overlay permission
3. Choose "Freeform" mode if available

### Recommended Settings

```
Taskbar Settings → 
  → Position: Bottom
  → Start button: Show
  → Show recent apps: Yes
  → Enable freeform mode: Yes (if Android 7+)
```

### ADB Commands for Freeform (if needed)

Some devices need this for freeform windows:

```bash
adb shell settings put global enable_freeform_support 1
adb shell settings put global force_resizable_activities 1
```

Then reboot.

---

## Part 5: Kvaesitso Configuration

### Recommended Setup

1. Long-press home screen → Settings
2. **Search**:
   - Enable web search: Your choice
   - Local search: Apps, contacts, files
3. **Widgets**:
   - Add clock widget
   - Add Continuum Studio widget (once app installed)
4. **Appearance**:
   - Theme: Match system or dark
   - Icon pack: Your preference

### Large Screen Optimization

- Use landscape orientation for most productivity
- Position search bar at comfortable reach
- Add larger widgets on the sides

---

## Part 6: Kiosk Mode (Optional - Continuum Dedicated)

If you want the tablet dedicated to Continuum Studio:

### Simple: Screen Pinning

1. Settings → Security → Screen pinning → Enable
2. Open Continuum Studio
3. Tap Recents button
4. Tap pin icon on Continuum Studio card
5. To exit: Hold Back + Recents (or gesture equivalent)

### Advanced: Device Owner Mode

**Warning: Complex setup, usually requires factory reset**

Only do this if you want a locked-down kiosk. See F-Droid app "FreeKiosk" or similar.

---

## Troubleshooting

### ADB device not found

```bash
# Restart ADB server
adb kill-server
adb start-server
adb devices
```

### Disabled something important

```bash
# Re-enable all disabled packages
adb shell pm list packages -d | while read p; do
  pkg=$(echo $p | cut -d: -f2)
  adb shell pm enable $pkg
done
```

### Need to find T90 exact model

```bash
adb shell getprop ro.product.model
adb shell getprop ro.product.brand
adb shell getprop ro.board.platform
```

---

## Device Information Commands

Run these to identify your tablet for future reference:

```bash
# Basic info
adb shell getprop ro.product.model
adb shell getprop ro.product.brand
adb shell getprop ro.build.version.release

# Chipset (for ROM compatibility)
adb shell getprop ro.board.platform
adb shell getprop ro.hardware

# Treble check (for GSI compatibility)
adb shell getprop ro.treble.enabled
```

---

## Next Steps

After basic setup is complete:
1. Install Continuum Studio tablet app (when ready)
2. Configure Tailscale for homelab access
3. Test dialog connectivity
4. Set up TTS for accessibility mode

---

*Generated by Synapsix session - April 2026*
