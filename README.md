# TV Remote for Xiaomi MiTV-AXFR2 (Android TV 11)

A native Kotlin + Jetpack Compose phone app that controls your TV directly over home Wi-Fi with
the **Android TV Remote protocol v2**, the same protocol the Google TV phone app uses. It needs no laptop, server, cloud,
account, root, ADB, developer mode or router changes.

- **Download:** `TVRemote-1.1.1.apk` from this repository's [Releases](https://github.com/Abhinesh7846/TV-remote/releases) page.
  1.1.1 is functionally identical to 1.1.0; it only replaces device-specific example text with generic placeholders.
- It updates older versions in place: same app ID `com.abhinesh.tvremote`, same signing key (SHA-256 `74b22b7f…35ba`), so pairing and settings are kept.
- **Source:** this folder (`core/` protocol and voice logic, `app/` Android app, `cli/` laptop test tool).
- Permissions: Internet (local network only), network state, microphone (asked only when you use voice).

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
- **TV voice search** streams audio only to your TV.
- The microphone runs only while you hold the button. It stops on release, slide-off, leaving the screen or the app, or after 15 s.
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
