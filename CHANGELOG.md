# Changelog

## [0.2.1] - fork

- Dialogs: left-aligned text, steps and shell commands in numbered boxes, and buttons whose text stays
  readable when focused (it was light text on a light box). The Sleep After Music setup uses the short
  component name, `io.github.besliky.airplaytv/.service.SleepService`.
- The Now Playing backdrop is dithered once its colours hold still, which removes the diagonal bands a
  dark 8-bit gradient shows on an OLED.
- Lyrics: a lookup that fails (offline, rate limit, server error) is retried and, if it keeps failing,
  reported as "Can't reach the lyrics service" instead of "No lyrics found". Titles with decoration
  ("(feat. X)", "- Remastered 2011") are searched again without it, then with only the first artist.
  Another cut of a song gives its words as plain lyrics (time-stamped lines still need a length within
  10 s of the track's).
- Preview modes for the lyrics states, the dialogs and the banding comparison.
- The loud speaker icon was cut off at its right edge (its outer wave was drawn past the icon's frame), and
  it was clipped again whenever it swelled on a volume change. Both fixed.
- OLED care did not get past its first stage in real use: the sender repeats its volume every time a stream
  starts, and that reset the idle clock. Repeats are ignored (and no longer undo a volume set with the TV
  remote); only a volume that moved counts as someone at the controls. New ladder: dim after 3 min, a minimal
  display (song, progress, times; dim, drifting, on black) after 8 min, nothing lit after 30 min, display
  allowed to sleep after 15 min paused. A new song peeks for a few seconds without resetting the clock.
- Starting the app from the Home screen while music plays opens the player; Menu opens the settings from it.
- Return To Player (on by default): the player takes over from the system screensaver while music plays, starting
  in its minimal stage, and a media key on the remote shows it for six seconds while it is hidden.
- Advanced: "Appears On iPhone As" (Apple TV, TV, Apple TV 4K) chooses the model the receiver advertises,
  which is what an iPhone draws the AirPlay icon from.

## [0.2.0] - fork

See the "What this fork adds" table in the README. In short: wake from sleep, a Now Playing screen with
track info, cover, progress, transport buttons and volume; pause detection; remote control through DACP;
one shared volume; animations; opt-in lyrics; OLED care; opt-in sleep after music; AirPlay photos;
stable release signing. The native core gained tests for the metadata, remote identity and photo paths.

## [0.1.0] - 2026-09-30

First release.

- AirPlay screen mirroring receiver for Android TV and Google TV (Android 8.0+)
- H.264 mirroring up to 1080p60, H.265 up to 4K with a hardware HEVC decoder
- Hardware decoding with MediaCodec straight to the display surface, low-latency
  decoder configuration, newest-frame rendering, decoder kept alive in the background
- Audio: AAC-ELD while mirroring, ALAC and AAC-LC for music, sender volume control
- Bonjour discovery through Android's mDNS responder, re-announced on network
  changes and wake-up, with duplicate device names resolved automatically
- Background receiver: foreground service, start on boot, recovery after process
  death and app updates, automatic opening of the playback screen
- Optional PIN pairing with remembered devices and pairing reset
- TV interface for the D-pad: status, device name, AirPlay on/off, PIN, autostart,
  connection info, advanced settings, performance overlay, diagnostics export
- Local-network-only connections, Keystore-protected identity, no analytics
