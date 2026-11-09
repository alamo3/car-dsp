# CabinEQ (car-dsp)

An Android EQ built for the car: a static EQ, speed-dependent compensation, and
(later) cabin measurement and content-aware dynamic EQ. Rootless.

## Status: milestone 3a — native DSP

- 10-band EQ (31 Hz–16 kHz) + preamp, applied with `DynamicsProcessing`
- Output limiter at −1 dBFS, and a **preamp recommendation** that cuts by your largest EQ
  boost so the EQ can't clip (clipping is the most common source of crackle). Auto preamp
  (on by default) keeps it at the recommended value; turn it off to set the preamp yourself,
  and the app warns you if it's above the recommendation.
- **Speed compensation** from GPS. It boosts mainly the lows and low-mids, where EV road and
  tyre noise sits, with a smoothstep ramp between a start speed and a full speed. Speed is
  smoothed (τ = 3 s) and the gain changes are slew-limited, so the adjustments can't be heard.
  The last speed is held for 30 s when GPS drops out (tunnels).
- "Simulate speed" slider for testing the curve while parked
- Live status panel: attached sessions, speed, applied gains

### Native DSP engine (default)

Music app → playback capture → **C++ engine** (preamp → 10 biquads → lookahead limiter) →
our AudioTrack → Bluetooth / Android Auto. The app's own output is muted with a −200 dB
effect on its session (capture taps the audio before effects, so it still gets the full
signal). Filters are designed in Kotlin (`EqDesign.kt`, RBJ cookbook) and run in C++
(`src/main/cpp`).

- Android asks for screen-capture permission every time DSP starts. Only audio is used.
- Apps that opt out of capture (Spotify, Chrome, …) are detected (media playing but the
  capture is silent) and automatically use the Android EQ path instead.
- **Speaker safety:** if our output is routed to the phone speaker, DSP stops and everything
  falls back to the Android EQ. This guards against the Android Auto problem seen with
  RootlessJamesDSP. Our output allows capture by system components, so Android Auto can
  carry it to the car. A toggle under Testing allows speaker output on purpose.

### Music player (Nebula / TorBox → native EQ)

"Music — search & play" on the main screen opens a player that streams audio straight through
the same C++ engine:

HTTPS audio link → `MediaExtractor`/`MediaCodec` decode → stereo float → **C++ engine** →
AudioTrack. No playback capture and no screen-capture prompt: the engine gets the decoded
source samples, at the file's own sample rate (44.1/48/96 kHz), with float decoding so 24-bit
sources stay 24-bit. Consecutive tracks at the same rate share one AudioTrack (gapless).

- **Search** goes through an [Eclipse addon](https://eclipsemusic.app/docs) such as **Nebula**:
  paste the same install link you use in Eclipse (Settings → Connections). Results are songs and
  albums from Apple Music's catalog, so there is no video. When a song plays, the addon finds it
  on TorBox and returns a direct audio link; while TorBox is still caching it, the addon answers
  "not ready" and the player shows its progress and retries. The link carries your TorBox key, so
  the app hides it and only ever shows the host.
  TorBox's own search API is gone (`search-api.torbox.app` no longer resolves, and the main API
  has no search endpoint), which is why search goes through the addon.
- **My TorBox library** lists torrents already on your account (`/api/torrents/mylist`); video-only
  ones are hidden. Opening one **auto-selects the tracks**: lossless (FLAC/WAV) if present,
  otherwise the most common lossy format (or the format you pick under "Library format"); junk
  (samples, `__MACOSX`, tiny files) is dropped; tracks are naturally sorted by path so multi-disc
  albums play in order. APE, WavPack, AIFF and DSD are skipped — Android can't decode them.
- **Magnet links** pasted into the search box are added to TorBox (`/api/torrents/createtorrent`),
  then played once TorBox has them.
- **The queue survives a restart.** Tracks now carry a stable source id instead of a lambda, so the
  queue, current track and position are saved: reopen the app and the mini player is where you left
  it, and play resumes mid-track. That stable id is also what a browse tree (Android Auto) and
  favourites/offline will use.
- Playback gets the live EQ, preamp, limiter and speed compensation, a MediaSession (steering-wheel
  / Bluetooth next/prev), notification controls and audio focus (pauses for calls, ducks for
  navigation). Same speaker safety as DSP: it pauses if the output lands on the phone speaker,
  unless allowed under Testing.

Roadmap:
- 3b: de-clicker and content-aware dynamic EQ in the C++ engine
- 2: cabin measurement (sine sweep, multi-position averaging, correction curve)

## Android Auto / Automotive

CabinEQ is a media source in the car: **For you**, **Recently played** and **My TorBox** are
browsable, the car's search finds anything on your addon, and the assistant can start anything
("play One More Time on CabinEQ").

- The root only shows sources that exist: **For you** (more from the artist you last played —
  top tracks plus albums; the addon has no catalog feed, so this is derived from history),
  **Recently played** (a real play history kept across sessions, not just the current queue),
  and **My TorBox** only when a TorBox key is set. Empty tabs in a car are just noise.
- Search uses `MediaBrowserServiceCompat.onSearch` (the framework service has no search API at
  all, hence the one dependency): songs first, then albums to browse into. Past searches are
  also a browse node (**Search**) so a car with no keyboard can replay them in one tap.
- Media buttons are received even when nothing is running (`MediaButtonReceiver` + a *mutable*
  PendingIntent — an immutable one makes the system's KeyEvent send fail), so a steering-wheel
  or headset Play starts the service and the restored queue.

- `EqService` **is** the `MediaBrowserService` the car binds: one service for the EQ, the in-app
  player and browsing, so a play request from the car never needs a background service start.
- Browse ids carry their source (`trb:<torrent>#<file>`, `alb:<album>#<track>`, `q:<index>`), so
  the tree survives a process restart, and selecting a track queues its whole parent rather than
  a queue of one.
- The car opens icons itself, in its own process, so `ArtworkProvider` serves covers as
  `content://` URIs from the same cache the phone UI uses.
- The session publishes the queue, the active item, the buffered position and a session activity,
  so the car has "up next", a moving progress bar and a way back into the app.
- The framework `MediaBrowserService` has no `onSearch` (that exists only in Media3 / the support
  library), so search goes through `androidx.media`; the car's on-screen keyboard search and the
  voice path (`onPlayFromSearch`) both work.
- Test with the Desktop Head Unit. **Production cars only list apps installed from the Play
  Store**, so a GitHub-release build won't appear there — use a Play internal-testing track.
  (This app is not Play-distributed: Android Auto's media drawer and AAOS's Media Center still
  list sideloaded media apps, but the Google Assistant will not offer us for "play X on
  CabinEQ" — that list is Google's, not something a manifest flag unlocks.)

### Android Automotive OS

AAOS runs the car UI on the device, so it needs two things beyond the above:

- The media browser service must **opt in** with
  `<meta-data android:name="androidx.car.app.launchable" android:value="true" />`
  (`MediaSource.isMediaTemplate`); without it CarMedia never lists the app.
- The car UI runs in **user 10** on AAOS images, so the app's service process is `u10_*` and
  reads `/data/user/10/…`. When seeding settings for a test, write them there (the image is
  userdebug, so `adb root` works).

A local AAOS emulator is enough to see the real car UI: system image
`android-35-ext15;android-automotive;x86_64`, a hand-written AVD pointing at it, and the x86_64
APK (`-PcabinAbis=x86_64`).

## Build

Open the folder in Android Studio and run on the device. If Studio suggests an AGP or
Gradle upgrade, accept it. The only library dependency is `androidx.media`, for the car's media
browser search API (see below); everything else is framework-only.

Builds are arm64 only, like every current phone. To run on an x86_64 emulator, add the ABI:
`./gradlew installDebug -PcabinAbis=x86_64`.

## How it attaches to audio

Music apps broadcast `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` when they start a playback
session, and CabinEQ attaches to that session. Things to know:

- **Start CabinEQ before you start the music.** If the session list says "none yet", pause
  the music app or close and reopen it so it announces the session again.
- Spotify, Samsung Music, Poweramp and most local players send the broadcast. Some streaming
  apps don't. For those, try **Global mode** (session 0). Android has deprecated it, so test
  whether it works on One UI.
- Effects follow the session, so the EQ applies whether the audio goes out over Bluetooth or
  Android Auto. Navigation prompts use their own session and aren't affected.
- If a session shows "control lost", another effect (Samsung's Dolby Atmos/Adapt Sound or
  another EQ app) took over. Turn that effect off.

## Car notes

- If your head unit has its own speed-dependent volume setting, turn it off so the two don't
  stack.
- Set the head unit's EQ flat while tuning here.
- Samsung: set the app's battery usage to **Unrestricted** so One UI doesn't kill the service
  while you drive.
