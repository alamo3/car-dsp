# CabinEQ (car-dsp)

An Android EQ built for the car: a static EQ, speed-dependent compensation, and
(later) cabin measurement and content-aware dynamic EQ. Rootless.

### Lyrics

The phone's full player has a **Lyrics** button, and the car's now-playing screen a **Lyrics**
action: both show the song's lyrics with the current line highlighted and followed as it plays.

Lyrics come from [LRCLIB](https://lrclib.net) (free, no key, line-level timecodes), because the
addon has no lyrics resource. Lookups are cached in `filesDir/lyrics` — a hit for a month, a
miss for a week — so repeats and instrumentals never hit the network twice. When only untimed
lyrics exist they are shown without highlighting; when the song has none at all the screen says
so, and a failed lookup says that instead (it is not cached).

In the car this is a plain `ListTemplate` (Android Auto has no lyrics surface): a fixed 8-row
window of timed lines, the current one marked and tappable to seek there, refreshed about once
a second — the fastest a host reliably accepts. Templates want short text by design, so the
phone view remains the full one.


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

### Output path (what the mixer does to our audio)

Below **Status** on the Equalizer & DSP screen, **Output path** reports the stages a stream
goes through on its way to the speaker — read-only, nothing there changes the sound:

```
Source:      FLAC 44.1 kHz / 16-bit
Track:       PCM float · 44.1 kHz · stereo
Device:      48.0 kHz · 192 frames/buffer
Resample:    44.1 → 48.0 kHz, by the system mixer
Volume:      6/15 (40%) — not at maximum; the mixer attenuates
Direct:      no — this format is mixed
Spatial:     on
Effects:     none attached by CabinEQ
Route:       phone speaker · SM-F956W
```

Everything comes from public API (`PROPERTY_OUTPUT_SAMPLE_RATE`, `AudioTrack.getRoutedDevice`,
`getDirectPlaybackSupport`, `getSupportedMixerAttributes`, `Spatializer`), so it can be trusted
when asking "is something in the way". Two things are deliberately *not* claimed:

- **Volume dB**: the platform's `getStreamVolumeDb` reports −0.1 dB at 6/15 on the Fold 6 while
  the mixer shows −25 dB on the track, so the index is shown instead of a false number.
- **Vendor processing**: Dolby/SoundAlive runs in Samsung's HAL (`mDsEnable=1` there) and is
  invisible to the framework; the panel says to check Settings → Sounds instead of guessing.

**Copy report** puts the same text on the clipboard.

### Sample-rate matching

By default CabinEQ emits at the rate Android mixes at (48 kHz on the speaker, whatever the
route reports), resampling in its own DSP when the source differs — so the Android mixer has
nothing to convert. The alternative — handing it 44.1 kHz and letting the platform convert —
is one line in **Equalizer & DSP → Output path → Match device rate**, and the panel says which
side did the resampling.

The resampler is `app/src/main/cpp/resampler.h`: exact-rational polyphase (phase count is
outRate/gcd, so there are no interpolated phases and no drifting accumulator), Kaiser-windowed
sinc, 128 taps, cutoff placed so the stop-band starts exactly at the lower Nyquist. Measured
offline (`tools/resampler-test.cpp`):

| test | result |
|---|---|
| 44.1 → 48 kHz, 100 Hz–19 kHz | 118–132 dB SNR |
| block sizes 1 / 37 / 512 / 1024 / 4096 | identical results (no boundary artifacts) |
| 48 → 44.1 kHz, 0–20 kHz | flat, −9.0 dBFS in → −9.0 dBFS out |
| 48 → 44.1 kHz, ≥ 22.05 kHz | −60 dB or lower, −230 dB at 24 kHz |
| DC | gain error −0.01 dB |

It costs about 10 % of one core for the whole player thread (FLAC decode + EQ + limiter +
resampling); the resampler is the smaller part. The tail is flushed at the end of a track so
the last milliseconds are not cut, and a route change (headphones, USB DAC, Bluetooth) rebuilds
the output at the new rate instead of silently resampling twice.

### Volume in the car

When car mode starts (Android Auto, AAOS, or a car dock), the media stream is set to maximum
so the mixer's stream-volume attenuation is out of the path before the audio leaves the phone;
the car's own volume then sets the level. It is put back when car mode ends.

That touches the user's own volume, so it is deliberately conservative:

- only in car mode, and only with **Volume to max in the car** on (default on);
- skipped when `AudioManager.isVolumeFixed` — the head unit owns the volume there and setting
  it does nothing but fight the car;
- skipped over Bluetooth, where the phone's stream volume *is* the car's volume (absolute
  volume), so "max" would mean "turn the car to maximum";
- skipped into the phone's own speaker unless **…also with the phone speaker** is enabled —
  that is the one case where this can blow your ears off;
- applied **once** per car session, so turning it down afterwards sticks, and restored only if
  it is still at the maximum we set.

`ENTER/EXIT_CAR_MODE` are implicit broadcasts, which a manifest receiver never receives since
API 26, so the receiver is registered at runtime while the player is alive; the state is also
re-checked when the player starts and when the output route changes. If the process dies mid
session the volume stays where it was set, and the next start finds `preCarVolume` and undoes
it.

### Click-proofing and metering

Three things guard the output, and one number tells you whether they work:

- **Fade-in at every (re)start** — track start, seek, rebuilt sink. Starting mid-waveform at
  full amplitude is a step, and a step is a click; 15 ms of ramp hides it and is inaudible.
- **Ramped limiter attack** — the gain now reaches its target over the lookahead, so it lands
  exactly when the peak leaves the delay line. (Measured before: the step-based attack left a
  discontinuity; now the output peak lands on the threshold with no jump of its own.)
- **True-peak and click meters** in the Output panel. True peak is a 6-tap windowed-sinc
  reconstruction (within ~0.15 dB of a 4× meter), which is what the DAC's filter sees between
  samples. "Clicks" counts one-sample jumps that tower over the recent signal — fast cymbals
  don't count, a discontinuity does.

The click counter is the honest part: after 3 skips and 2 seeks it reads **1**, because cutting
playback with `pauseAndFlush()` steps from the current amplitude to zero. Fixing that last one
means holding the output tail and shrinking the AudioTrack buffer (200 ms → ~40 ms) so a cut
lands near the write position — a latency trade worth asking about, not something to slip in.

### Bluetooth high-frequency guard

A2DP carries roughly 15–16 kHz of usable bandwidth. Bright material — and quite a lot of
torrent-sourced "hi-res" is bright, with near-full-scale bursts at 20–22 kHz — turns into
crackle over Bluetooth, because what arrives in that band is codec noise. With the guard on
(default), the output is low-passed at 15.5 kHz (24 dB/oct, two Butterworth sections) **only
when the routed device is Bluetooth**, before the codec sees it:

| frequency | attenuation |
|---|---|
| 1 kHz | −0.2 dB |
| 12 kHz | −1.4 dB |
| 15.5 kHz | −6.2 dB |
| 20 kHz | −29.7 dB |
| 22 kHz | −54.1 dB |

Wired, USB and speaker routes are untouched. The Output panel shows `HF guard: armed
(Bluetooth only)` or `on — Bluetooth, low-pass at 15.5 kHz`, and the switch is on the same
screen. The route is only known once a track is playing, so the parameters are re-pushed at
that point rather than rebuilding the sink.

### Importing playlists (taste from other services)

The big services do not let another app read your playlists — YouTube Music, Spotify and Samsung
Music all declare a **non-exported** `MediaBrowserService`, so there is no API, permission or
trick by which CabinEQ can pull them. That is deliberate on their side.

So import is an **export**: Music → ⚙ → **Import playlists**

- **YouTube Music** (no public API): in Soundiiz or TuneMyMusic connect YouTube Music and export a
  playlist to CSV/JSON. Google Takeout works too but its CSVs are video-id-only, with no titles.
- **Spotify**: [Exportify](https://exportify.net) exports CSV **including ISRCs**, which matches
  almost exactly.
- Anything else: a plain `Artist - Title` list pasted in, of any length.

Three ways in: paste, **pick a file** (SAF, no permission prompt), or **share** a CSV/JSON to
CabinEQ from any file manager. `cabineq://import` and
<https://cabineq-jam-81cd8.web.app/import.html> open the same screen.

Matching goes through the addon, **one search per distinct artist** rather than per track, ISRC
first and then title-token coverage with a 0.75 floor; the result reports what it could not find
(`312 rows · 241 matched · 71 not in the catalogue`) instead of silently seeding half a list.

Imported tracks become `Taste.Kind.IMPORT` events: **0.35** each, against a play's 0.25–1 and a
like's 6, so an imported playlist nudges the profile without overruling what you actually listen
to. Re-importing the same playlist does not double it, and **Forget imported playlists** removes
every import in one tap while keeping your real history.

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

### Installing a local build over the CI build

CI publishes `dev.alamo3.cabineq` signed with the key in the `SIGNING_KEYSTORE_B64` secret. For a
local build to *replace* that app (rather than failing with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
or ending up as a second copy), it must use the same key and package:

```sh
# if you have the existing key (the SIGNING_KEYSTORE_B64 secret value):
export SIGNING_KEYSTORE_B64='<the secret value>'
tools/import-signing-key.sh                      # -> ~/.android/cabineq-release.jks

# if you need a new key (the old one is lost, or this is the first release):
tools/make-signing-key.sh                        # asks for a password, prints the base64 for CI

echo 'cabineq.storePassword=<SIGNING_PASSWORD>' >> local.properties   # never committed
./gradlew installDebug -PcabinVersionCode=9999   # no -PcabinAppIdSuffix: same package
```

`app/build.gradle.kts` reads the key from `SIGNING_KEYSTORE_PATH`/`SIGNING_PASSWORD` (CI), from
`local.properties` (`cabineq.storeFile`, `cabineq.storePassword`, optional `cabineq.keyAlias` and
`cabineq.keyPassword`), or from `~/.android/cabineq-release.jks` — and signs **debug and release**
with it. The gradle log line at configuration time says which key is in use.

Two things to keep in mind:

- **Version code.** CI sets it from the workflow run number; a local build must use a number that
  is not lower than the installed one (`-PcabinVersionCode=9999` is a safe dev value). CI releases
  will then refuse to install over that dev build until you bump past it.
- **Side-by-side testing is opt-in.** `-PcabinAppIdSuffix=.aaTest` installs the build as a
  separate package (`dev.alamo3.cabineq.aaTest`) next to the release app — useful to compare both
  in Android Auto, but that is what creates a second icon/copy.

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
