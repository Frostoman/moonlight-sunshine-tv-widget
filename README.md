# Moonlight Android TV Widget

A lightweight **Android TV** app that puts your PC game library — streamed from a
**Moonlight-compatible host** — right on the home screen as a **preview channel** (the Android
TV "widget"). Selecting a game launches it through
**[Moonlight](https://github.com/moonlight-stream/moonlight-android)**.

> 🇺🇦 Українською: [README.uk.md](README.uk.md)

![Home screen channel](docs/screenshots/home_channel.png)

## About this project

I'm **not a programmer** — I built this entirely with **Claude Code**, originally just for
myself. I'm sharing it in case it's useful to someone else.

**Pray for Ukraine!** 💙💛 🇺🇦

Moonlight's developers are welcome to use it however they like — and it would be great to see
this kind of home-screen integration built into Moonlight itself. I've done everything I wanted
with it, so the source is open for anyone to take and adapt. 🙂

## Features

- 🎮 Shows your PC game library as cards on the Android TV **home screen** (preview channel).
- 🖼️ Pulls **box art** for each game.
- ▶️ One click **launches the game via Moonlight** (no built-in streaming — Moonlight does that).
- ✅ **Per-game checkboxes** — choose which games appear in the widget.
- 🔤 **Alphabetical** ordering.
- ✏️ Customizable **widget name**.
- 🌐 **English** and **Ukrainian** localizations.
- 🔄 Periodic background refresh of the game list + box art.

## Screenshots

| Home-screen widget | In-app library | Select games |
|---|---|---|
| ![](docs/screenshots/home_channel.png) | ![](docs/screenshots/library.png) | ![](docs/screenshots/select_games.png) |

## How it works

```
This app ──(its own pairing: client cert + PIN)──► the host on your PC
   │  reads /applist + box art over the GameStream protocol
   ▼
Local cache ──► Preview channel (cards) on the Android TV home screen
   │
   ▼ (you click a card)
Intent → com.limelight.ShortcutTrampoline (PC UUID + AppId)
   │
   ▼
Moonlight streams the game
```

### Two separate pairings — and why

Android sandboxes apps, so a non-rooted app **cannot read Moonlight's data**. Therefore:

- **This app** pairs with the host **on its own** (separate PIN) — only to read the game list and box art.
- **Moonlight** must **also** be paired with the same PC — it does the actual streaming.

Both see the same PC `UUID` (from `/serverinfo`), so handing that UUID to Moonlight's
`ShortcutTrampoline` resolves the correct PC in Moonlight's database.

## Requirements

- Android TV **API 21+** (the home-screen channel requires **API 26+ / Android 8**).
- **[Moonlight](https://play.google.com/store/apps/details?id=com.limelight)** installed on the TV, with your PC already added & paired in it.
- A PC running a **Moonlight-compatible host** with **PIN pairing** enabled.
- Both devices on the same network.

## Install

1. Download the latest **`WidgetAndroidTV-x.y.apk`** from the [Releases](../../releases) page.
2. Sideload it onto your Android TV, either:
   - **ADB:** `adb connect <TV-IP>:5555` then `adb install WidgetAndroidTV-x.y.apk`, or
   - **USB / file manager:** copy the APK to the TV and open it (enable "install from unknown sources").

## Setup

1. Open your host's Web UI (often at `https://localhost:47990`) and keep the **PIN** tab ready.
2. On the TV, open **Games** → **Settings** row → **Add / pair PC**.
3. Enter the PC's IP → **Connect** → a 4-digit PIN appears.
4. Type that PIN into your host's Web UI. The game list syncs automatically.
5. Make sure the **same PC is also paired inside Moonlight** (it does the streaming).
6. Add the **Games** channel to the home screen via the launcher's "Customize channels".
7. Optional: **Settings → Select games** to choose which games show, and **Widget name** to set a label.

## Build from source

Requirements: **JDK 17**, **Android SDK** (compile SDK 34). The Gradle wrapper is included.

```bash
# Windows
gradlew.bat assembleDebug
# macOS / Linux
./gradlew assembleDebug
```

The APK is produced at `app/build/outputs/apk/debug/WidgetAndroidTV-debug.apk`.
Open the project in **Android Studio** for the easiest setup (it bundles a JDK).

## Known limitations

- Some custom launchers (e.g. Mi TV Home) prefix the channel with the app label and a colon
  (`Games:`); this is launcher-controlled and can't be removed via the channel API. Stock
  Android TV shows it cleanly.
- Box art on the home-screen row depends on launcher support for content-URI poster art; the
  in-app library always renders it.
- Supports **one PC** at a time. No mDNS auto-discovery — the IP is entered manually.
- Wake-on-LAN is handled by Moonlight (via `ShortcutTrampoline`).

## License & attribution

Licensed under the **GNU General Public License v3.0** — see [LICENSE](LICENSE).

The GameStream **pairing handshake and HTTP protocol** logic is ported from
**[moonlight-android](https://github.com/moonlight-stream/moonlight-android)** (GPLv3), which
makes this project a derivative work and is why it is GPLv3.
