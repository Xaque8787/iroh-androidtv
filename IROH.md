# Iroh Android TV proof of concept

This fork adds one Iroh pairing to the existing Jellyfin Android TV client. Jellyfin
continues to authenticate users. Android 7.0/API 24 or newer on ARM64, 32-bit ARM,
or x86_64 is required (32-bit x86 is excluded). The debug
application ID is `io.github.xaque8787.iroh.androidtv.debug`, and the launcher name is
**Iroh Jellyfin TV (debug)**. It can coexist with the official app.

## Build an APK

In GitHub Actions, select **App / Build → Run workflow** on the branch containing
this integration. It runs unit tests, assembles a debug APK, and uploads the
`iroh-androidtv-debug` artifact (14-day retention). APK builds are manual; existing
lint and test workflows remain automatic. No release signing secrets are needed.
CI debug signing keys can differ between runs: if Android rejects an update with
a signature mismatch, uninstall the previous test app first (this deletes its
pairing and login). No automatic SDK tracking or Rust build is involved.

For local builds, install JDK 21 and the Android SDK required by the existing
Gradle version catalog, then run:

```sh
bash scripts/fetch-iroh-sdk.sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The script downloads SDK 0.4.0 from `Xaque8787/iroh-jellyfin` release `v0.4.0`,
checks a pinned SHA-256, and puts the AAR in ignored `app/libs`. The consumer uses
its existing coroutine dependency. To update the SDK, update the version and
checksum in the script and the file dependency together.

## Connect

Paste a `jellyfin-iroh://invite/...` link in the existing server field and connect.
After device pairing, use the normal Jellyfin login or Quick Connect flow. HTTP(S)
server entries still work normally. The app retains the Iroh identity and pairing
in app-private no-backup storage; it stores no invite or proxy capability URL.
A logical internal address identifies the transport in the Jellyfin server store.
The proxy is started lazily for requests, including after process death.

If pairing succeeds but connecting to Jellyfin fails, use **Connect using saved
Iroh pairing** to retry without redeeming the invite again. To replace an existing
Iroh server, remove its saved server entry first. Removal forgets local pairing
but does not revoke the device on the host. An invitation may be consumed even
when the final connection fails; obtain a fresh invitation if pairing was refused.

## Playback and limitations

Both built-in player implementations share the routed HTTP data source. External
player selection is bypassed while an Iroh server is active. Encoded paths, query
strings, Jellyfin authorization, WebSocket handshakes and Range headers are retained.
Root-relative and absolute HTTP redirects are routed underneath the capability.
HLS absolute URLs (including key/subtitle URI attributes) are mapped to the logical
origin; relative paths resolve against the final logical manifest URL. HLS manifests
are limited to 2 MiB. Video/segment bodies remain streamed.

During Iroh playback, HTTP media URLs are routed to the paired host, including URLs
advertising a LAN/public origin. Independently hosted media, DRM/license servers,
and third-party streams may therefore fail. DASH resources use the same routed
HTTP data source, but there is no general XML/manifest rewriting layer. Cookies
retain the SDK's documented origin/path limitations. This is best-effort compatibility,
not a claim that every Jellyfin playback mode has been validated.

The endpoint belongs to the application process, so navigating away from an
Activity does not close playback streams. Network callbacks notify Iroh of changes.
Interrupted playback may require retry/resume; gapless network migration is not
promised. Do not share debug logs containing Jellyfin tokens or invite links.

## Device acceptance checks

- Install alongside the official application; verify both launch independently.
- Pair, log in with Jellyfin credentials and Quick Connect, browse artwork.
- Direct play, seek forward/backward, pause/resume; verify a transcoded HLS stream.
- Test subtitle tracks (external and embedded), audio playback and WebSockets.
- Force-stop/reopen, automatic sign-in and playback using retained pairing.
- Disconnect/reconnect Wi-Fi and retry playback; test public-relay connectivity.
- Remove the Iroh server and pair a new invite; verify ordinary HTTP(S) servers.

The URL-routing unit test uses a local HTTP server to exercise redirect confinement,
encoded paths, HLS keys and byte-range/header forwarding without native Iroh.
Real host/TV playback still requires the checks above.

## Local validation

The debug APK assembled successfully, and all 94 app unit tests passed, including
the two HTTP routing integration tests. APK inspection confirmed API 24 minimum,
the distinct debug application ID and all three supported Iroh native libraries;
the APK signature verifies. The release AAR was independently downloaded and its
checksum matched the published checksum. Modified workflows passed actionlint
(using a local runner-label declaration for ubuntu-26.04, which the installed
older actionlint does not yet recognize).

No Android device/emulator was connected. Native pairing, actual Jellyfin login,
TV UI behavior and real playback remain device acceptance checks; the unit tests
do not establish those. The GitHub Actions workflow has not been run remotely.
