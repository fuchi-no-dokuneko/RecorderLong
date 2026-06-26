# RecorderLong

Minimal Android voice recorder designed for long sessions.

- Records from the microphone in a foreground service.
- Stops automatically after a configurable number of minutes; default is 6 hours.
- Saves audio as 1-minute `.m4a` parts in public `Download/RecorderLong`.
- When recording stops, creates concatenated hourly `.m4a` files named like
  `hour_YYYYMMDD_HHMMSS_h01.m4a` in the same session folder.
- Keeps already completed parts intact if the phone shuts down during a later part.
- Uses a foreground service and partial wake lock to keep recording alive in the background.
- Supports Android 8 through Android 15.
- Includes silent/minimal notification, Do Not Disturb, and call-screening controls.

Call rejection requires granting RecorderLong the Android call-screening role. Without that role, Android will not let a normal app reject calls.

Build:

```bash
./gradlew assembleDebug
```

Install on a connected Android device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Offline PC noise removal:

```bash
tools/setup_offline_audio_env.sh
.audio-env/bin/python tools/denoise_hour_offline.py /path/to/hour_YYYYMMDD_HHMMSS_h01.m4a
```

That DeepFilterNet pipeline writes:

```text
hour_YYYYMMDD_HHMMSS_h01_denoised.m4a
```

There is also a separate long-audio statistical/GMM pipeline:

```bash
.audio-env/bin/python tools/denoise_hour_gmm_offline.py /path/to/hour_YYYYMMDD_HHMMSS_h01.m4a
```

That writes:

```text
hour_YYYYMMDD_HHMMSS_h01_gmmdenoise.m4a
```

The GMM/statistical pipeline is designed for long audio and bounded RAM. It
does three streaming passes: frame-energy clustering, noise-spectrum averaging,
then chunked Wiener-style suppression. It does not load an hour of audio into
memory.

Rescue or rebuild a previous session:

```bash
.audio-env/bin/python tools/rescue_session_offline.py /path/to/RecorderLong --list
.audio-env/bin/python tools/rescue_session_offline.py /path/to/RecorderLong --session 1
```

You can also pass a session folder directly:

```bash
.audio-env/bin/python tools/rescue_session_offline.py /path/to/session-YYYYMMDD_HHMMSS
```

This validates completed minute parts, tries local repair for damaged parts,
skips unrecoverable files, and writes hourly files named:

```text
rescued_hour_YYYYMMDD_HHMMSS_h01.m4a
```

All PC tools use local ffmpeg, local Python, and local model/filter code. They
do not upload audio.
