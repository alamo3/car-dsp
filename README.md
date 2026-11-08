# CabinEQ (car-dsp)

An Android EQ built for the car: a static EQ, speed-dependent compensation, and
(later) cabin measurement and content-aware dynamic EQ. Rootless.

## Status: milestone 1

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

Roadmap:
2. Cabin measurement: sine sweep, multi-position averaging, and a correction curve (USB
   measurement mic recommended)
3. Playback capture → Oboe/C++ chain: de-clicker, content-aware dynamic EQ

## Build

Open the folder in Android Studio and run on the device. If Studio suggests an AGP or
Gradle upgrade, accept it. The project has no library dependencies.

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
