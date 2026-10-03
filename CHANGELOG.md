# Changelog

## [0.2.1] - fork

- **rc.19.**
  - **The TV no longer stays on its home screen after the Menu button.** The TV's own menu closes by itself after a while; a key sent to
    it afterwards (Back, OK, or the Exit that closes the menu) could land on the input instead and send the TV to its LG home screen. While
    the menu is in use (and four seconds after) a second connection watches the TV's front app; if a TV that was on an HDMI input goes to
    its home screen, it is put straight back on that input.
- **rc.18.**
  - **A quick panel for the LG TV, on a double press of the Menu button.** A single press still opens the TV's own settings (with the
    arrow keys, OK and Back steering them), as before; pressing Menu twice opens a list over the screen: *LG TV Settings* (the same menu),
    a *scene* (Movie, Game, Music, Night), the *input* (HDMI 2 · PS5, pinned by name), the *picture mode*, the *sound output*, the *volume*,
    *Music Mode* (the TV's screen off while music plays), *Screen Off Now*, a *sleep timer* (30, 60 or 90 minutes) and *Turn The TV Off*
    (asks twice). The top line says what the TV is doing now. Opening it only reads; nothing changes until a row is used. A single press
    waits a third of a second to see whether a second follows; TV Features, Double-Press Menu switches the panel (and that wait) off.
  - **Scenes** set the input, picture mode, sound output, a volume that is never raised (only lowered to "at most") and the screen in one
    go; what each does is set under TV Features, TV Scenes. Defaults: Movie = Cinema picture; Game = the input the TV names like a PlayStation
    and Game picture; Music = screen off; Night = Cinema picture and volume at most 8.
  - **Sleep timer.** The TV goes to standby and the stick sleeps (a phone playing to it is let go first). Five minutes and one minute before
    the end a notice shows on the stick's screen and as the TV's own toast (so a game or a film shows it too); any key in the last minute adds
    15 minutes. It survives a restart of the app.
  - **Hold Menu** (TV Features, Hold Menu; off unless chosen): TV off and the stick asleep after a three-second countdown that any key cancels,
    the quick panel, or the Game scene.
  - **TV Notices:** a short notice at the top of the screen for what the stick does to the TV (a scene, a sleep timer, a picture mode);
    TV Features, TV Notices switches them off.
  - **Auto Picture Mode** (off unless chosen): when the TV goes to the chosen input (PlayStation by name, or an HDMI number) its picture
    mode is set to the chosen one. The TV keeps one picture mode per input, so nothing is put back on leaving.
  - The picture modes follow the signal: a TV showing Dolby Vision (`dolbyHdrGame`) or HDR10 keeps a separate set of modes, so the
    panel cycles through the set the TV is in, and a scene's "Cinema" becomes Dolby Vision Cinema there. The inputs the panel offers are
    those with something connected and those you have named (a switched-off PS5 is still where Game goes).
  - Picture mode is changed through the TV's settings service when the direct request is refused (newer webOS), and every change is read
    back, so a TV that ignores it is reported, not assumed.
- **rc.17.**
  - **No bars along the bottom of Now Playing.** The visualizer is off unless it is switched on in Settings, Visualizer.
- **rc.16.**
  - **The TV's volume cannot stay low after a session that ended badly.** The receiver remembers the volume it set on the TV; when a
    session ends without giving the volume back (the app was killed, the link dropped), the next session finds the TV still at that
    volume and puts it back to what it was before, instead of taking the low value as the new starting point.
  - **AirPlay 2** (debug builds only): a fourth trial mode (clock bit only), a longer request trace, and the test server can advertise
    itself on a Mac. Still hidden and off in release builds.
- **rc.15.**
  - **The TV keeps its volume when the phone's slider begins low.** The TV's own volume followed the slider between zero and what the TV
    had when the phone connected, so a phone whose slider began a third of the way up (a phone that has not played to this receiver
    before does that) turned a TV that was at 15 down to 5 before a note had played, and nothing could be heard. Now the TV stays where
    it is for as long as the slider stands where it began; the slider turns it down from there and back up to it, never above what the
    TV had, and a night ceiling still holds it lower.
  - **AirPlay 2 for music** (debug builds only): an iPhone that chooses the route and plays nothing sets a stream up and takes it down
    again at once; that no longer starts the audio output (sound focus, the playing screen, the TV's volume): the output starts with the
    first block that is due. The phone's volume reaches a running buffered stream (it was dropped). The features say that artwork is
    wanted. The log names the fields of the track information and the artwork that arrive, and counts the blocks of each stream.
- **rc.14.**
  - **Another app on the TV takes the sound.** The output asks for audio focus and listens: when the YouTube app (or any player that asks
    for the sound) starts, the receiver goes silent at once and asks the phone to pause (DACP `pause`, the toggle only for a sender that does
    not know it); when the phone plays again, the sound comes back. A moment of silence for a voice prompt only mutes, a prompt speaking over
    the music only ducks it. Before, the receiver listened to nothing, so the music went on under the other app.
  - **No burst when the phone moves to its own speaker.** The TV's volume went back up the moment the session ended, while the last sound
    was still on its way through the HDMI chain to the TV's speakers, so the tail of the music came out loud. The output now goes silent first
    and the TV's volume goes back 0.9 seconds later (at once when the next session starts).
  - **AirPlay 2 for music** (debug builds, Advanced, AirPlay 2 (Music); off by default, a trial that switches itself off): transient
    pairing and the sealed control channel, the event connection, realtime audio and **buffered audio** (the stream an iPhone asks for:
    TCP blocks sealed with ChaCha20-Poly1305, paced by the receiver because there is no PTP clock to follow), ALAC and AAC-LC at 44100 and
    48000 Hz (the audio output now plays either rate). Tried with a test sender and on the stick; an iPhone pairs, sets up its stream and
    takes it down again at once (it expects its clock packets, PTP on UDP 319 and 320, to be answered, which an app cannot do without
    root), so there is no sound from a phone yet; the receiver now answers like shairport-sync does (an updateInfo message on the event
    connection, the clock peer addresses, a no-PTP mode to try) and that is being worked on. See firetv-4kmax `docs/AIRPLAY2.md`.
- **rc.13.** The log shows what a sender asks of the receiver (the first 80 requests of each connection: method, address, a few harmless
  headers, the text of a parameter request and the shape of a property-list body; never a pairing or FairPlay body, a name or an
  address), and a request the receiver does not know is a warning instead of silence. It is how the question whether the phone's
  volume slider can follow the receiver without AirPlay 2 is answered from one ordinary session (see the research note in firetv-4kmax,
  `docs/AIRPLAY2.md`). No behaviour changes.
- **rc.12.** The home screen's chip gets what it needs: the receiver's broadcast now carries the phone's name, the song's length and place
  in it, and a small cover, and the receiver takes `COMMAND` broadcasts (play and pause, next, previous, seek) from the home screen's small
  player. They only do what the remote's media keys do, and only while a sender plays.
- **Wake the TV.** With the LG paired (TV Settings Button), a sender that connects while the TV is off, or on standby with its network up,
  makes the receiver press Home through the key service. A Home press makes the stick send "Text View On" and "Active Source" over
  HDMI-CEC (seen in `dumpsys hdmi_control`), which switches a sleeping LG on and puts it on the stick: that is why the owner's Home press
  woke the TV when AirPlay did not, the stick being awake already so that no wake-up of its own happens. No setting of the TV is needed
  beyond HDMI-CEC (SimpLink); the key service must be on. The receiver also sends Wake-on-LAN when it knows the TV's address (the TV's own
  setting "Turn on via Wi-Fi"), puts the TV on this stick's input when that is known, brings the player back to the front after the Home
  press, and asks the phone to wait with the music until the picture is up. The input and the TV's hardware address are learned at the end
  of pairing, and by themselves whenever the receiver talks to the TV: the address from the TV, the input when exactly one connected input is
  named like a Fire TV (the TV's input list is logged, so a TV that names its inputs otherwise can be told by pressing TV Features, The
  Stick's TV Input). Settings, TV Features, Wake The TV.
- **TV features:** the TV's own volume follows the phone's slider and the remote's volume keys when the TV lets it move, between zero and
  the volume the TV had when the phone connected (so a phone's slider can never take a TV that was at 15 to 70; a volume changed on the TV
  with its own remote moves the top of the slider along, and the TV goes back to its own volume when the session ends); a TV whose sound
  goes to a fixed output keeps the receiver's own volume; Music Mode turns the TV's screen off while only music plays and back on at the next
  key; Smart Pause pauses the phone when the TV is switched to another input or off.
- **Volume limits** (Settings, Volume Limits): a maximum, a lower maximum between two hours at night, and a start level, so a song never
  begins loud. The whole slider is scaled into the ceiling.
- **Sound** (Settings, Sound): bass, treble, loudness and a night mode that evens out loud and quiet, with the system's audio effects on the
  music's track. Each effect is tried on its own; one a device cannot carry out is logged and left out.
- **Second phone** (Settings, When A Second Phone Plays): it takes over (as before), the current phone is kept, or the TV asks.
- **Self-check that heals:** once a minute while idle the receiver checks that its port answers and its name is announced, restarts when the
  port is silent and announces the name again when it was lost for two checks. Advanced, Check The Receiver says in plain words why a phone
  might not see the TV (port, name, 2.4 GHz band, a VPN in the way).
- **Now Playing:** a calm visualizer along the bottom edge (Settings, Visualizer), a resting stage while the music has been paused for a minute
  (the picture dims and drifts more slowly), and a bubble with the time a seek will land on.
- **AirPlay video** (Advanced, AirPlay Video, on by default): the cast button in apps such as Safari and YouTube gives the receiver the address
  of the video (`/play`), and a new screen plays it with the platform's player (HLS and files over https) while the phone reads where it is
  (`/playback-info`) and sets play, pause and position (`/rate`, `/scrub`). Unverified on a phone; tried on a Fire TV Stick 4K Max against the
  test sender and Apple's HLS sample stream (play, pause, seek, a 4:3 picture keeps its shape). Addresses
  that are not http(s) are refused; a plain http address fails because the app does not allow cleartext traffic.
- Photos were also tried over the network on the stick with real JPEGs (4032x2268 and square): the photo fits the screen, the next one dissolves
  in, photos sent ahead (`cacheOnly`) show on `displayCached`, an unknown key is answered 412 and a body that is no image 400, and the end of the
  session closes the screen. Not yet seen from the real Photos app; slideshows are still refused.
- What's new: a card after an update, from `res/raw/whats_new.txt`.
- Debug builds install next to the release build (`.dev` suffix, named "AirPlay TV Dev"), so a build can be tried without replacing the app in use.
- The stray `TvTrust.kt.bak` is gone from the tree.

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
