# Native FFT (whisperfft)

This folder builds the `whisperfft` JNI library used by `NativeFft` to speed up
mel spectrogram generation for Whisper STT.

Implementation details:
- Bluestein FFT to handle non power-of-two size `400`.
- Power spectrum folded to `n_fft/2 + 1` bins to match Whisper filters.
- Multi-threaded frame processing (one worker per requested thread).

