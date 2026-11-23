# Bundled local speech classifier

`silero-v6.2.3.onnx` is the official Silero VAD ONNX model, pinned to upstream
commit `5cd7945676eb32225748052e2e6a0580e4686a08` (Silero VAD `v6.2.3`). It is
bundled in this APK and is never downloaded at runtime.

- Upstream model: <https://github.com/snakers4/silero-vad/blob/5cd7945676eb32225748052e2e6a0580e4686a08/src/silero_vad/data/silero_vad.onnx>
- SHA-256: `1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3`
- Size: 2,327,524 bytes
- License: MIT; the upstream maintainer explicitly confirmed the weights are MIT at <https://github.com/snakers4/silero-vad/discussions/817>. The license text is in [`LICENSE-SILERO.txt`](LICENSE-SILERO.txt).
- Wrapper contract: the pinned upstream `src/silero_vad/utils_vad.py` accepts 512 new float32 samples at 16 kHz plus 64 samples of prior context, state `[2, batch, 128]`, and scalar `int64` sample rate `16000`. It returns a model score and next recurrent state.
- Runtime: ONNX Runtime on-device CPU inference. Microphone samples and recurrent state remain in memory.
