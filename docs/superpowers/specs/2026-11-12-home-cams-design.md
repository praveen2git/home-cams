# Home Cams - Design

Date: 2026-11-12
Status: approved (plan). Milestone 0 results pending.

## Context

Cameras are moving out of Home TV into a new Android TV app. It is a camera wall, PTZ and NVR playback app for several brands (Benefio, Hikvision, CP Plus, ONVIF cameras such as EZVIZ), and it must run well on low-end TVs.

Reasons for a separate app (user choice):
- It is lighter and faster on low-end TVs.
- It gets its own home-screen icon.
- It can be given to other homes without Jio.

Home TV loses its cameras.

User answers:
- **Users:** my home first, other homes later.
- **Target TVs:** Fire TV AFTSSS (Android 9) plus cheap Android TVs in general.
- **Playback v1:** pick a camera, a day and a time, then play.
- **PTZ:** any camera that supports ONVIF PTZ.

Read-only probe of the NVR at 192.168.1.200 (2026-11-12):
- It is a **TVT box inside**: NVMS web firmware dated 2025-02-19, `IDCS_*` strings, TVT port 6036.
- **Open ports:** 80 and 6036 only.
- **RTSP (554) is closed** and ONVIF does not answer. That explains the 401s and failures seen on 2026-11-05.
- Camera 192.168.1.201 has 80 and 554 open.

## Identity

| Item | Value |
|------|-------|
| App name | Home Cams |
| Package | `com.praveen.homecams` |
| Repo | `praveen2git/home-cams` (public, so the updater needs no token) |
| Folder | `projects/general/home-cams` |
| Min / target SDK | 24 / 36 |

## Approach: RTSP for everything, ONVIF only where needed

v1 uses no private brand API. Live video and playback are RTSP URLs built per brand. PTZ uses ONVIF only. This keeps each brand to a small URL builder that is easy to test.

```kotlin
interface Brand {                       // one object per brand
    fun liveUri(src: Source, ch: Int, sub: Boolean): String
    fun playbackUri(src: Source, ch: Int, start: LocalDateTime): String?
}
```

| Brand | Live | Playback |
|-------|------|----------|
| Hikvision | `/Streaming/Channels/{ch}01` (sub `{ch}02`) | `/Streaming/tracks/{ch}01?starttime=YYYYMMDDTHHMMSSZ` |
| Dahua / CP Plus | `/cam/realmonitor?channel={ch}&subtype=0/1` | `/cam/playback?channel={ch}&subtype=0&starttime=YYYY_MM_DD_HH_MM_SS` |
| TVT / Benefio | worked out in Milestone 0 | worked out in Milestone 0 |
| ONVIF camera (EZVIZ etc.) | from ONVIF GetStreamUri (reuse Home TV code) | none |
| RTSP URL | the URL the user typed | none |

- **Source:** `{id, name, brand, host, rtspPort, user, pass, channelCount, onvifXaddr?}`.
- **Channel count:** the user types it when adding an NVR (default 8). There is no channel-list API in v1. A channel with no camera shows "No video".
- **Hikvision and Dahua formats are from their public docs** and are untested until someone with that hardware tries them. The UI labels them "untested".

## Reuse from Home TV (copy and adapt; the code is ours)

- `home-tv/app/src/main/java/com/praveen/hometv/cameras/`:
  - `OnvifSoap.kt` (WS-UsernameToken digest, profiles, stream URI parsing).
  - `OnvifClient.kt` (`describe()`, clock offset).
  - Discovery probe with a multicast lock.
  - `CameraStore.kt` (JSON in DataStore).
- `home-tv/app/src/main/java/com/praveen/hometv/update/` (GitHub release updater).
- `home-tv/app/build.gradle.kts`: release signing from `keystore.properties`, R8, toolchain 17. The gradle version catalog.
- `ui/theme`, the `AppContainer` manual DI pattern, and `EventRecorder.kt` for tests.
- The camera grid player setup in `home-tv/.../ui/cameras/CamerasScreen.kt`: muted sub-stream ExoPlayer per tile, released when leaving.
- Use a **new signing key** for Home Cams (a separate app needs its own key). Back it up like the Home TV key.

## Low-end TV rules

- **Tiles:** each tile plays the **sub stream** through RTSP over TCP on a `SurfaceView`. Use a small `DefaultLoadControl` buffer and no Coil.
- **Live tile cap:** default 4, changeable in Settings up to 9. Only layouts that fit the cap can be picked (1, 4, 6, 9). Arrow keys page through the channels.
- **Players:** a player is released when its tile leaves the screen. Full screen uses one main-stream player and stops the wall players first.
- **Errors:** a decoder or network failure shows that tile's own reason. It never takes down the whole wall.

## Milestones (each ends with a signed APK and a GitHub release)

**0. Hardware check (needs the user; they will enable RTSP later)**
Milestone 1 code that does not depend on TVT formats can start before this. That covers the skeleton, sources, wall, and the Hik/Dahua/ONVIF/RTSP brands. Only the TVT brand object waits for the results.
1. The user enables RTSP in the Benefio NVR menu (Network > Port) and confirms the NVR login. Optional: enable ONVIF / platform access.
2. From the PC, test live and playback RTSP URLs with ffprobe or VLC, once per format. Find the TVT URL formats. Read `js/` on the NVR web page if needed.
3. Test ONVIF PTZ once on one EZVIZ camera.
4. Record the results in the spec. If TVT playback can't be found, Milestone 3 ships for direct cameras and Hik/Dahua only, and says so.

**1. Base + live wall (v0.1.0)**
- Project skeleton, signing, updater, theme.
- Sources screen: add an NVR (brand, IP, port, login, channel count), find ONVIF cameras, add an RTSP URL, edit, delete.
- Camera wall and full screen (Up/Down for next/previous, Back to the wall).
- Settings: live tile cap, layout.
- Start on the wall.

**2. PTZ (v0.2.0)**
- ONVIF PTZ in `OnvifSoap`: GetNodes / GetConfigurations to detect support, `ContinuousMove`, `Stop`.
- In full screen, OK opens PTZ mode only if the camera supports PTZ. D-pad pans and tilts. Channel up/down zooms. Releasing the key sends Stop. Back leaves.
- PTZ works on cameras added directly with ONVIF. It also works on NVR channels if the NVR answers ONVIF. Otherwise it is hidden.

**3. Playback (v0.3.0)**
- From full screen choose Playback. Pick a day (last 14 days), then a time in 10-minute steps, then play `playbackUri`.
- Left/Right jumps -1/+1 minute. Rewind/FF jumps -10/+10 minutes, by restarting the URL at the new time.
- No speed control: Media3 RTSP does not support the Scale header.
- An empty or failed result says "No recording at this time".

**4. Remove cameras from Home TV (Home TV v0.6.0)**
- Delete from Home TV:
  - `cameras/` and `ui/cameras/`.
  - The camera box in the options panel.
  - Settings > Cameras.
  - The Home menu "Cameras" entry and its route.
  - `POST /api/camera-login` in `remote/RemoteApi.kt` and `assets/remote.html`.
  - `CHANGE_WIFI_MULTICAST_STATE` in the manifest.
  - Wiring in `AppContainer.kt`, `MainActivity.kt` and `PlayerController.kt`.
  - The camera tests.
- The Home menu "Cameras" entry stays only when Home Cams is installed (`packageManager.getLaunchIntentForPackage("com.praveen.homecams")`). It opens Home Cams.
- Update Home TV's README, HANDOFF and memory.

**Skipped for v1:** phone remote for typing passwords, motion events, a recordings timeline, downloads, playback speed, 2-way audio, cloud-only cameras (Ring, Nest, Blink, Wyze), and TVT/Hik/Dahua private APIs.

## Verification

- **Unit tests:**
  - Each brand's live and playback URL builder, including password encoding.
  - ONVIF PTZ envelopes and parsing.
  - Source store round trip.
  - Tile cap and layout rules.
- `./gradlew testDebugUnitTest assembleRelease`, then `apksigner verify`.
- **Emulator:** walk through add source, wall, full screen, Settings. The emulator can't reach cameras, so tiles show their error reason.
- **Real Fire TV** (ask before installing):
  - The wall with 4 tiles on the Benefio.
  - Watch `adb shell dumpsys meminfo com.praveen.homecams` and `top` for 10 minutes.
  - Full screen, PTZ on an EZVIZ camera, playback from yesterday.
- **Home TV v0.6.0:** the tests pass, the Home menu has no Cameras entry without Home Cams and opens it when installed, and live TV is unaffected.

