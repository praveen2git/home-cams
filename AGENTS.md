# Home Cams - notes for agents

Android TV camera app (Kotlin, Compose, Media3). Read `README.md` and the design in `docs/superpowers/specs/`.

## Rules

- **The app is generic. Never show a brand name in the UI** (no Benefio, Hikvision, Dahua, CP Plus, TVT). NVR formats are "NVR type 1/2/3". Brand names may appear only in code and docs.
- **An NVR channel is named "Cam N"** on the wall (the NVR's default name is "Cam").
- **Low-end TVs come first.** Wall tiles play the sub stream, muted, with a small buffer. Never raise the live tile cap past 9. No image library.
- **A failed camera shows its own reason on its own tile.** It must never block or crash the rest of the wall.
- Use plain hyphens only, never long dashes.

## Layout

- `sources/` - `Source`, `Brand` (one RTSP URL builder per NVR format), `Config`, wall paging rules, JSON storage.
- `onvif/` - ONVIF SOAP as plain text (discovery, stream addresses, PTZ). No XML library.
- `update/` - GitHub release updater.
- `ui/` - screens. `CamPlayer` is the only place that creates a player.

## Checks

- `./gradlew testDebugUnitTest assembleRelease`
- Then walk the app on a TV emulator: add a source, wall, full screen, Settings. Ask before installing on a real TV.
