# TV Remote for Xiaomi MiTV-AXFR2 (Android TV 11)

A native Kotlin + Jetpack Compose phone app that controls your TV directly over home Wi-Fi with
the **Android TV Remote protocol v2**, the same protocol the Google TV phone app uses. It needs no laptop, server, cloud,
account, root, ADB, developer mode or router changes.

- **Current version: 1.4.0** (versionCode 6). APKs are published on this repository's [Releases](https://github.com/Abhinesh7846/TV-remote/releases) page, or build one from source (below).
- It updates older versions in place: same app ID `com.abhinesh.tvremote`, same signing key (SHA-256 `74b22b7f…35ba`), so pairing and settings are kept.
- **Source:** this folder (`core/` protocol and voice logic, `app/` Android app, `cli/` laptop test tool).
- Permissions: Internet (local network only), network state, microphone (asked only when you use voice).

## What's new in 1.2–1.4

### 1.4.0: voice fixed, Google Assistant, more apps
- **Hold to speak works** (confirmed on a vivo V70 Elite). Before, the app ran a slow offline-support check (up to 3 s) before opening the mic and silently gave up if you let go first.
  - Now the mic opens immediately. Tap or hold: it stops by itself when you finish talking.
  - Engines are tried in order: Google's speech service, the phone's default, on-device. Any engine that doesn't answer within 4 s is skipped.
  - Google's own voice popup is the last fallback.
- **Assistant button** on the Remote tab, plus the voice command "open Assistant" (also in Telugu and Hindi). It opens Google Assistant on the TV with the Search key; tested on the MiTV-AXFR2.
  - The TV accepts voice audio streamed from a phone app but ignores it (tested at 8 and 16 kHz), so spoken TV commands go through the phone's recogniser instead.
- **More apps:** Sony LIV, ZEE5, aha, ETV Win, Sun NXT, Spotify and Apple TV, each with voice names in English, Telugu and Hindi.
  - Launch links tested on the TV: `sonyliv://`, `https://www.zee5.com`, `aha://`, `spotify://` and `https://tv.apple.com` open the app.
  - Every shortcut ends with its Play Store page (`market://details?id=…`, which this TV accepts), so a missing app can be installed from there.
  - `market://launch`, `android-app://` and `intent:` links are refused by this TV.
- Existing shortcut lists gain the new apps on update; edits and custom apps are kept.

### 1.3.0: Graphite & Copper redesign
- New look: graphite background, copper accents, Manrope / Noto Sans Telugu / Noto Sans Devanagari fonts (OFL, in `licenses/`).
- Five tabs: Remote, Apps, Voice, Send, Routines. Settings is behind the gear on the Remote tab.
- **Routines** (Movie Night, Gaming, Quiet Mode, or your own) run steps through the same verified executor as voice. The Gaming routine's HDMI 2 key hasn't been checked on this TV.
- Fixes found in review:
  - the connection no longer drops when moving from the Share panel to the app;
  - cancelling pairing no longer shows a false error;
  - Back leaves the pairing screen.
- `app/src/test/.../ScreenshotTest.kt` renders all five screens at the phone's size (Robolectric + Roborazzi) into `app/screenshots/`.

### 1.2.0: Send to TV
- Share a YouTube, Netflix, JioHotstar or Prime Video link to **Open on TV**, or paste one on the Send tab.
- Voice can search inside apps ("play Pushpa on YouTube").

## What changed in 1.1.0

### Voice: causes found and fixed

| Failure point | What was wrong | Fix |
|---|---|---|
| Speech engine choice | If the phone had any on-device recogniser, the app always used it, even for a language it lacks (often Telugu). The app never fell back to anything else | Checks each language's on-device support (Android 13+) and uses the phone's speech service when it's missing. If the on-device recogniser rejects a language while you're still holding the button, it switches automatically |
| Microphone timing | The button looked ready before the mic was listening, so the first word was lost. "Volume up" arrived as "up"; "close Netflix" arrived as "Netflix" and *opened* it | Amber **Wait…**, then red **Speak now** with a vibration once the mic is really listening |
| Command interpretation | First-keyword matching: whatever word matched first won | New interpreter that reads **action + target** from the whole sentence in English, Telugu and Hindi, including romanised and mixed speech. Contradictions, two apps, or two commands at once produce a question with answer buttons; nothing runs silently |
| App switching | A second launch was silently ignored while the first was still waiting for confirmation | A new launch replaces the pending one |
| Execution status | Only "sent" was shown | Each command shows what you said, the engine and language used, what it understood, and the result: **✓ confirmed by the TV**, ➜ sent, • nothing to do, ? needs your answer, or ✗ failed with a reason |

Supported commands:
- "Open/launch/switch to" any shortcut. "Close this app", "close it" or "close Netflix" go to the Home screen. This doesn't force-stop the app, and "close Netflix" does nothing when Netflix isn't the app in front.
- Volume up/down with optional counts. Mute and unmute check the TV's mute state first.
- Home, Back, arrows, OK, play, pause, stop, next, previous, fast-forward, rewind, settings, input, TV off and on.
- "Open it" uses an app you named in the last 2 minutes, otherwise the app asks which app.

Examples:
- Telugu: "సౌండ్ పెంచు", "యూట్యూబ్ ఓపెన్ చెయ్యి", "ఈ యాప్ క్లోజ్ చేయి"
- Hindi: "आवाज़ बढ़ाओ", "यूट्यूब खोलो", "ये ऐप बंद करो"

Languages:
- Explicit English, Telugu and Hindi.
- **Auto-detect** appears only on Android 14+. It's labelled *untested* until your phone's recogniser actually reports a detected language, and is withdrawn automatically if it never does.

### Power: diagnosis

- In standby your TV **switches its Wi-Fi off completely**. Tested from the laptop: no ping reply, no remote port, nothing in the router-level (ARP) table.
- **Wake-on-LAN** packets to both of its MAC addresses (its Wi-Fi address and the one in its certificate) did **not** wake it.
- So power-on from the phone is **not possible in this standby mode**. No app on any phone can do it while the TV is off the network.
- "Turn off" now uses the explicit Sleep key (and Power only when the TV has reported it's on) and reports success only when the TV confirms.
- "Turn on" uses the Wake key when the TV is reachable; otherwise it says plainly that the TV isn't on the network. It never claims "TV turned on" just because a command was sent.
- The app retries quickly (every 5 s or less, and immediately when Wi-Fi reconnects), so after you switch the TV on with its remote, it reconnects by itself.
- Not yet checked: whether the TV's settings have an option that keeps Wi-Fi on in standby (see "When you're back at the TV").

## Feature status

Status meanings:
- **Passed (phone + TV)**: tested today with v1.1.0 on your vivo and MiTV-AXFR2.
- **Passed (laptop + TV)**: tested on the TV using this project's protocol code from the laptop tool.
- **Automated**: covered by the 46 tests against a simulated TV. That isn't proof it works on your hardware.
- **Unverified**: implemented, waiting for your test.

| Feature | Status |
|---|---|
| Update over 1.0.0 keeps pairing | **Passed (phone + TV)**: connected with no new code |
| Reconnect after switching the TV on with its remote | **Passed (phone + TV)** |
| Turn off (standby), verified | **Passed (phone + TV)**: "The TV went to standby and left the network" |
| Turn on while the TV is in standby | **Unsupported on this TV** (Wi-Fi off in standby; Wake-on-LAN got no response). The app says so honestly: **passed (phone + TV)** |
| Turn on while the TV is reachable (Wake key, verified) | Automated; unverified on hardware (this TV is never reachable in standby) |
| Pairing, wrong code caught, Home, D-pad, volume up/down | Passed (laptop + TV); Home and D-pad also used with 1.0.0 on the phone |
| Netflix (`netflix://`), Prime Video, JioHotstar (`hotstar://`) launches | Passed (laptop + TV) |
| YouTube launch | Passed (laptop + TV), via the TV's "Open with" chooser until you pick YouTube and **Always** |
| Package-name launching (`market://`) | Unsupported on this TV (refused); not used |
| Voice: English / Telugu / Hindi commands | Interpreter: automated (10 test groups, all three languages plus mixed speech). **On the phone: unverified** |
| Voice: app switching, "close" to Home, never opening on "close" | Automated end-to-end against the simulated TV; **unverified on hardware** |
| Voice: volume, mute/unmute with TV-state checks | Automated; **unverified on hardware**, including whether your TV reports its mute state |
| On-device vs online recognition per language on your phone | **Unverified**: Voice tab › *Speech engine and languages* shows what your phone reports |
| Auto language detection | Offered only on Android 14+, and **unverified** until your phone proves it |
| TV voice search (mic audio to the TV's Assistant) | TV accepts the session and audio (laptop + TV). Recognition **unverified** |
| A TV setting that keeps Wi-Fi on in standby | **Not checked yet** |
| Temporary debugging | Never enabled. USB debugging wasn't needed; files were copied in File transfer mode |

## When you're back at the TV: one step at a time

Keep the app open on the **Voice** tab. For each step, hold the mic, wait for red **Speak now**, speak, then let go. Note what the result line says.

1. **English:** "Open YouTube". You should get ✓ (or "? pick YouTube on the TV" if the chooser appears).
2. "Open Netflix". It should switch from YouTube to Netflix.
3. "Close Netflix". The TV goes to the Home screen.
4. "Close Netflix" again. This time: "Netflix isn't open… nothing was closed."
5. "Increase volume", then "decrease volume". You should see "Volume 17 → 20" and back.
6. "Mute", then "unmute". Tell me whether you get ✓ or ➜, which shows whether your TV reports its mute state.
7. **Telugu** (tap the తెలుగు chip): "యూట్యూబ్ ఓపెన్ చెయ్యి", "సౌండ్ పెంచు", "సౌండ్ తగ్గించు", "ఈ యాప్ క్లోజ్ చేయి".
8. **Hindi** (tap the हिन्दी chip): "यूट्यूब खोलो", "आवाज़ बढ़ाओ", "आवाज़ कम करो", "ये ऐप बंद करो".
9. A tricky one: "open it" right after step 8 should open YouTube. After 2 minutes of silence it should ask which app.
10. **TV settings:** on the TV, open Settings › System › Power & energy (or Power). Tell me whether you see anything like *Network standby*, *Wake on Wi-Fi/LAN*, *Energy modes* or *Quick start*. If one exists, it's the only way phone power-on could work.

If a step fails, send me a screenshot of the Voice tab result card. It shows the transcript, engine, language and reason.

## Install or update on your Vivo phone

1. Connect the USB cable and choose **File transfer**; or send the APK to yourself.
2. Put the APK in **Download**, then tap it in **File Manager**.
3. If asked, allow installs **from File Manager only** (a per-app permission, not a global security setting).
4. If Play Protect warns about an unknown app, tap **More details › Install anyway**. Don't disable Play Protect.
5. For updates, tap **Update**. Never uninstall first; that deletes the pairing.

## Privacy

- **Phone commands:** recognised on-device when your phone has the language installed. Otherwise your phone's speech service (usually Google) handles it, and may process the audio online.
  The app shows which engine was used for each command. *On-device only* blocks the online path.
- If the in-app recognisers fail, Google's voice popup (the Google app) is used for that one command.
- The microphone runs only for one command. It stops when you finish speaking, tap again, slide off the button, leave the screen or the app, or after 15 s.
- Logs record only event types and error codes; never audio, transcripts, keys or certificates.

## Known limitations

- Power-on is impossible while the TV is in standby with Wi-Fi off (this TV's current behaviour), and always impossible when it's switched off at the wall.
- Your TV shows an app chooser for YouTube links until you pick YouTube and **Always**.
- Home Assistant's docs note that some keys don't work inside the Netflix app with this protocol; Google's own TV app has the same problem.
- If the TV keeps showing "Use keyboard on mobile device", turn off *Settings › Keyboard and current-app detection*. Launch and close confirmation then can't work.

## Forget TV and reset

- *Settings › Forget TV* deletes the phone's record. To remove the phone on the TV side, go to TV *Settings › Apps › See all apps › Show system apps › Android TV Remote Service › Clear storage*.
- *Settings › Reset phone identity* creates a new key, so the TV must be paired again.

## Build from source

```bash
gradlew.bat :core:test
```

```bash
gradlew.bat :app:assembleRelease
```

The output is `app\build\outputs\apk\release\app-release.apk`. Raise `versionCode` in `app/build.gradle.kts` for every update.
The signing key is in `keystore\` with its passwords in `keystore.properties` (both git-ignored). **Back up both privately**: an update signed with a different key can't install over the app, and you'd have to uninstall and pair again.

### Layout

- `core/voice/VoiceInterpreter.kt` is the multilingual concept lexicon and action/target resolution.
- `core/voice/VoiceExecutor.kt` runs commands and verifies them from the TV's reports.
- `core/PowerControl.kt` handles verified standby and wake.
- `core/src/test` holds the simulated TV and the tests: protocol, pairing, reconnect, launches, voice parsing, voice execution, power.
- `app/voice/PhoneSpeech.kt` does engine selection per language, fallback, biasing, auto-detect verification, and microphone cleanup.
- `cli/` is the laptop harness used for the hardware tests.

## Sources and licences

- Protocol behaviour follows [tronikos/androidtvremote2](https://github.com/tronikos/androidtvremote2) (Apache-2.0) and Google's [polo pairing protocol](https://android.googlesource.com/platform/external/google-tv-pairing-protocol/) (Apache-2.0). This app is an independent Kotlin implementation.
- The speech APIs used (`checkRecognitionSupport`, `triggerModelDownload`, `EXTRA_BIASING_STRINGS`, language detection and switching, error codes 12/13) were checked against the Android 16 SDK (`android.jar`, API 36) on this laptop.
- Third-party libraries: AndroidX / Jetpack Compose and kotlinx.coroutines (Apache-2.0).
