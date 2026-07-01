# Credits and References

RecorderLong's application code is maintained in this repository. Development and optional offline tooling rely on or consult the following projects:

- [Android Open Source Project and Android SDK](https://source.android.com/) - platform APIs used by the recorder, foreground service, wake lock, call-screening, notifications, and storage code. Most AOSP code and documentation are available under Apache License 2.0; individual components may differ.
- [FFmpeg](https://ffmpeg.org/) - invoked as an external executable by the local audio conversion, concatenation, denoising, and rescue scripts. FFmpeg is primarily LGPL 2.1-or-later, with optional GPL-covered components depending on the build. See [FFmpeg legal information](https://ffmpeg.org/legal.html).
- [DeepFilterNet](https://github.com/Rikorose/DeepFilterNet) by Hendrik Schroter and contributors - optional local speech-enhancement engine called by `tools/denoise_hour_offline.py`; dual-licensed MIT or Apache-2.0. The project README contains its requested academic citations.
- [NumPy](https://numpy.org/) - numerical array operations in the optional statistical/GMM denoising script; BSD-3-Clause.

These dependencies and tools remain the work of their respective authors. RecorderLong does not vendor FFmpeg or DeepFilterNet.
