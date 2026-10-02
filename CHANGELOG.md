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
- A session that starts while the screensaver is showing now ends the screensaver: a running screensaver counts as
  "interactive", so the wake-up returned early and the player started underneath it.
- Return To Player (on by default): the player takes over from the system screensaver while music plays, starting
  in its minimal stage, and a media key on the remote shows it for six seconds while it is hidden.
- Song changes no longer look doubled or stutter. A skip arrives in steps (title, a 100-200 ms gap the sender reports as
  a pause, then album, progress and cover), and each step moved the cover: it shrank, sprang back and slid at once. A
  pause right after a song change is now held back and dropped if playing resumes; the same cover again (the next song
  of an album) stays put; a cover that arrives mid-slide replaces the one sliding in; a slow cover no longer clears the
  old one after 1.5 s (now 4 s); the album fades in instead of popping in. Cover decoding and its colours moved off the
  main thread, and unchanged media metadata is no longer republished half a dozen times per skip.
- Left and right on the remote's ring now seek instead of changing song: a tap jumps 10 seconds, holding scrubs faster the
  longer it is held, the bar shows where it will land, and the phone is asked once the keys have been quiet for 0.4 s
  (DACP `setproperty?dacp.playingtime`). Next and previous song stay on the media keys. If the phone's app does not honour a seek the
  player says so once and holding the key scans like an iPod's fast forward. The log tag `AirPlayTV-Seek` records what the sender answered.
- TV Settings Button (opt-in): the Fire remote's Menu button opens an LG TV's quick settings over the network (LG webOS remote
  protocol: find the TV, trust the certificate it presents, accept the prompt on the TV). Certificates are verified: nothing but the
  certificate the owner confirmed is trusted. In every app with the new Menu button service, an accessibility service that sees
  the Menu key only; the setup shows the commands, built to keep services that are already on.
- The Sleep After Music setup command now keeps accessibility services that are already on instead of replacing them.
- Lyrics: an opt-in second source. With "YouTube Music Lyrics" on, a song lrclib.net has no lyrics for is also looked up on
  YouTube Music (plain text, not time-stamped; the same title/artist leave the TV, to Google this time). The match needs the
  same title and one of the artists, and a length within 45 s of the track's. It uses the YouTube Music website's own web
  interface, which is not documented and may change; when it does the lookup finds nothing. Off by default, with a dialog that says so.
- Advanced > Appears On iPhone As: a trial for the speaker icon. Only the audio service is published, under a model iOS does not
  know, so an iPhone draws a speaker like the other targets instead of the Apple TV tile; only music works then. It undoes itself
  after 30 minutes unless music has started from an iPhone (a non-Apple model on the full service once made iOS refuse every
  session, which is why this is a trial and not an option that can strand the receiver).
- Preview mode `skip` plays three skips the way a real session delivers them, to check the song-change animation without a phone.
- The burn-in drift was a glide that never stopped, so the player (and the photo viewer) redrew at 60 frames per second
  for as long as they were up, using most of the stick's GPU. It now moves in short hops, and the settled backdrop no
  longer draws the gradient under its opaque dithered copy.
- TV Settings Button: the search for the TV now also asks every address of the local network directly (some devices do not carry
  multicast answers), holds a multicast lock while it looks, and when nothing answers it asks for the TV's address instead of
  ending in a message.
- TV Settings Button: the menu can now be used. The arrow keys, OK and Back of the Fire remote go to the stick, not to the TV, so the
  menu opened and then did not react. While it is open those keys are forwarded to the TV as its own buttons (over the open button
  socket, so a held arrow key scrolls); Menu again closes it, and ten seconds without a key give the keys back to the stick.
- TV Settings Button: pressing Menu while the TV does not answer no longer freezes the screen. The arrow keys, OK and Back were taken
  from the first press and only given back after about 17 seconds of waiting; now they stay the app's until the TV has answered, a
  TV that does not accept a connection within 2 seconds is reported at once ("Can't reach the TV. Is it on?"), and the search for a
  changed address happens afterwards in the background.
- Lyrics follow what is heard: the receiver compares the position the sender reports with the position of the newest audio packet
  minus what is still queued (ring and output buffer), keeps the median of the last measurements and shifts the lyrics by it. New
  setting Lyrics Timing (Automatic, or 0.25 s steps earlier or later up to 2 s) for songs cut differently from their lyrics. The
  log says what was measured (`sync:` lines).
- Volume stuck at 0 with the TV on HDMI-CEC: with CEC on, the stick treats the HDMI output as a fixed-volume device and ignores
  every change of the system volume, so the number the receiver reads as "a remote key was pressed" stayed at 0, and "eight steps
  down" was read at every look, pinning the receiver's volume to silence whatever the phone's slider said. The watch now checks that
  it can park the system volume in the middle and stays out of it when it cannot.
- One volume on a fixed-volume output (HDMI-CEC): the remote's volume and mute keys did nothing then, because Fire OS drops them. The
  Menu button service (the accessibility service) sees the keys before Fire OS does, so while a session runs on such an output it takes
  them and steps the receiver's level, the one the phone's slider also sets; with the service off, or on an output whose volume the
  system can move, nothing changes. A volume bar is drawn over the screen in front (an accessibility overlay: no permission of its own)
  whenever the level moves during a session, from the remote or the phone, except over the player, which has a bar of its own. The
  phone's slider is not moved by the remote (AirPlay gives the receiver no way to), its next change replaces the level.
- The "AirPlay" chip tells whether the music plays or is paused (the broadcast has a `playing` extra), so the home screen can show
  a pause mark and "Paused".
- Song changes are one motion. A skip arrives over a second, and each step still moved something (the title, the progress, the
  album, then the cover over the old one as a see-through overlay, then the backdrop's colours). The change is now held until the
  cover is in, then everything moves once, together, for 0.64 s: the new cover slides in over the old (both opaque, so the picture
  never dips), the three lines of text leave and come back line by line, the progress starts over and the colours move to the new
  cover's. A cover that arrives first, or the same album's, joins the same motion; a cover that never comes is given 1.1 s.
- Sleep After Music works with the Menu button service as well (one accessibility switch for both), and the setup commands it shows
  are that service's. The TV Settings Button page is a list of things to do instead of a list with a tick on its first line, shows
  whether the Menu button works in every app, and the setting says "On, in every app" or "On, in the player only".
- The "AirPlay" chip on the home screen app (tvhome): the receiver tells tvhome, and nothing else, when music or a mirrored screen
  starts and stops, and starting AirPlay TV from the home screen while something plays opens the player (or the mirrored picture).

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
