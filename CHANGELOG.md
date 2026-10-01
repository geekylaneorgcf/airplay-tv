# Changelog

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
