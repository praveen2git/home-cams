# Home Cams

An Android TV app that shows home cameras: a live camera wall, full screen, PTZ and NVR playback. It is built to run well on low-end TVs.

Design: [docs/superpowers/specs/2026-11-12-home-cams-design.md](docs/superpowers/specs/2026-11-12-home-cams-design.md)

## Using it

- **Tabs:** Live, Playback and Settings sit on top. On the wall, press Up from the top row to reach them, then Left/Right and OK.
- **Wall (Live):** arrows move and page through the cameras. OK opens a camera full screen. Menu opens Settings.
- **Full screen:** Up/Down changes camera. OK starts PTZ on a camera that can move. Left opens playback on an NVR that has it. Back returns to the wall.
- **PTZ:** hold an arrow to move, let go to stop. Channel up/down or FF/Rewind zooms.
- **Playback:** pick a camera in the Playback tab, then a day (last 14 days) and a time, press OK. Left/Right jumps 1 minute, Rewind/FF jumps 10 minutes.
- **Settings > Sources:** add an NVR (type, IP, port, login, channels), find ONVIF cameras, or add an RTSP address. The login typed on one found camera is used for the other found cameras too.

NVR types are numbered, not named. If the wall shows no video, try the next type.

## Build

```
./gradlew testDebugUnitTest assembleRelease
```

- Needs JDK 17 or newer and the Android SDK (`local.properties` with `sdk.dir`).
- A release build is signed when `keystore.properties` exists (git-ignored). It has `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.
- The APK is at `app/build/outputs/apk/release/app-release.apk`.

## Updates

The app checks the latest GitHub release of `praveen2git/home-cams` and installs its APK. Tag releases as `v0.2.0` and attach the signed APK.
