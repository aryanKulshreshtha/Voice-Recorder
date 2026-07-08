# Avatar overlay feature — build & run

This fork adds an optional live avatar overlay (powered by AvatarSDK) that renders during MP3
recordings. This doc covers building and running that feature locally. It only affects the
`core` and `gplay` build flavors — `foss` never depends on AvatarSDK and needs none of this.

## 1. Build the AvatarSDK AAR

The AAR isn't published anywhere — it's built from the sibling `AvatarSDK` repo and copied in
manually.

```powershell
cd path\to\AvatarSDK
.\gradlew.bat :sdk:assembleRelease
```

This produces `sdk\build\outputs\aar\sdk-release.aar`. Copy it into this repo as
`app\libs\avatarsdk-release.aar` (the exact filename `build.gradle.kts` expects):

```powershell
copy sdk\build\outputs\aar\sdk-release.aar path\to\Voice-Recorder\app\libs\avatarsdk-release.aar
```

Re-run these two steps any time you change AvatarSDK source — Voice-Recorder always builds
against whatever `avatarsdk-release.aar` happens to be sitting in `app/libs/`, so a stale copy
silently keeps old behavior.

## 2. Build Voice-Recorder

```powershell
cd path\to\Voice-Recorder
.\gradlew.bat assembleCoreDebug     # avatar-enabled build
.\gradlew.bat assembleGplayDebug    # avatar-enabled build
.\gradlew.bat assembleFossDebug     # no AvatarSDK at all
```

Output APKs land in `app\build\outputs\apk\<flavor>\debug\`.

## 3. Install and enable

```powershell
adb install -r app\build\outputs\apk\core\debug\voicerecorder-18-core-debug.apk
```

The avatar overlay only activates for **MP3** recordings (that's the only format that exposes
raw PCM for the avatar engine to consume — M4A/OGG go through `MediaRecorder`, which never
exposes a PCM buffer). On a fresh install the recording format defaults to M4A, so:

1. Open the app → **⋮** menu → **Settings**.
2. Scroll to **Extension**, select **mp3 (Experimental)**.
3. Go back and start a recording — the avatar overlay should appear.

This is the most common reason the avatar "isn't working": no saved preferences yet (fresh
install/data-clear) means the format is still M4A.

## Troubleshooting the avatar itself

If MP3 is selected and the overlay still doesn't render (times out, blank, etc.), that's almost
always an AvatarSDK-side issue, not a Voice-Recorder one — see `AvatarSDK/README.md`'s
**Troubleshooting** section, particularly around `<uses-native-library>` / QNN HTP backend
failures.

Useful logcat filter while testing:

```
adb logcat | grep -E "AvatarSessionHolder|StreamingSession|AvatarEngine|FrameEngine|QnnDsp"
```
