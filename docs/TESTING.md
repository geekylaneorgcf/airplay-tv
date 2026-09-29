# Testing

## Automated tests

| Suite | What it covers | Where it runs |
|---|---|---|
| `core/tests` unit tests | binary plist, RTSP parser, crypto known-answer vectors (NIST AES, RFC 7748 X25519, RFC 8032 Ed25519, SHA), pair-verify, SRP, FairPlay framing, mirror parsing (avcC/hvcC, AVCC→Annex-B), audio jitter buffer, ALAC | CI (clang, ASan + UBSan), any Android device via adb |
| `core/tests` loopback tests | complete sessions against the real server over sockets: legacy pairing, no pairing, PIN pairing (right and wrong PIN, remembered sender), sender takeover, receiver-side disconnect, sender vanishing, PIN hidden when the sender leaves, hostile and half-open connections, encrypted mirror and audio streams compared byte for byte | same |
| fuzzers | `bplist`, `rtsp`, `mirror`, `alac` and the complete request handler (`server`) with libFuzzer | CI, 60 s per target per run |
| JVM unit tests | name sanitising, settings keys, key validation, hex helpers | CI |
| instrumentation tests | the real foreground service answering RTSP over loopback, TXT records, malformed traffic, a device name already taken on the network (substitute name, name taken back once free) | Android TV emulator |
| lint | Android lint on the release variant, no issues allowed to accumulate | CI |

Run the native tests on a device or emulator without a Linux machine:

```
cmake -S core -B build-android -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=x86_64 -DANDROID_PLATFORM=android-26 -DANDROID_STL=none -DAIRPLAYTV_TESTS=ON
cmake --build build-android
adb push build-android/tests/airplaytv_tests /data/local/tmp/
adb shell chmod 755 /data/local/tmp/airplaytv_tests
adb shell /data/local/tmp/airplaytv_tests
```

## Test sender

`airplaytv_sender` (built with the tests) speaks the sender side of the protocol:
pairing, FairPlay framing, `SETUP`, the encrypted mirror stream and, with `--audio`, an
ALAC tone. The picture is a real H.264 stream generated without an encoder (I_PCM key
frames and skipped P frames), so any decoder can show it.

```
adb push build-android/tests/airplaytv_sender /data/local/tmp/
adb shell chmod 755 /data/local/tmp/airplaytv_sender
adb shell /data/local/tmp/airplaytv_sender 127.0.0.1 7000 20 --pair --audio
adb shell /data/local/tmp/airplaytv_sender 127.0.0.1 7000 10 --pin-screen   # with Require PIN on
```

The FairPlay key exchange cannot be reproduced without Apple's implementation; the
sender derives the stream key with the receiver's own playfair code, so both ends
agree on the key. Everything else is exercised exactly as with a real sender.

![Test stream on the emulator](images/test-stream.png)

## Verified on the Android TV emulator (API 36, x86_64)

- install, first launch, settings navigation with the D-pad
- service start, `_airplay._tcp` and `_raop._tcp` published once, IP shown
- a sender session with pairing: decoder created in the background, moved to the
  display surface when the activity appeared, picture shown with the right aspect
  ratio, ALAC audio to `AudioTrack`, clean teardown
- PIN screen shown on request and hidden when the sender leaves
- autostart after reboot (`BOOT_COMPLETED` → foreground service → published)
- restart after the process was killed
- receiver restart and re-publication after settings changes

## Needs a real TV and iPhone

- discovery and mirroring from iOS (FairPlay key exchange with a real sender)
- hardware decoders of TV SoCs (Amlogic, MediaTek, Realtek, Qualcomm), vendor
  low-latency modes, `setOutputSurface` support
- AAC-ELD mirroring audio and lip sync on HDMI outputs
- Wi-Fi reconnects and address changes (the emulator's Ethernet cannot be dropped
  without root), TV standby and wake
- 30+ minute sessions, rotation and lock/unlock on the phone, H.265 at 4K

A useful manual matrix: connect, disconnect with Back, reconnect; rotate the phone;
lock and unlock; switch apps on the phone; play a video (lip sync); switch the TV's
network; put the TV to sleep and wake it; reboot the TV; stream music from the Music
app; enable *Require PIN*, pair, reconnect without a PIN, reset pairing.
