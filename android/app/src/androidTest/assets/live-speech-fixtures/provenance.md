# Local speech-classifier fixtures

These PCM16 mono 16 kHz WAV files are the public synthetic test sentences defined in `LiveAudioCorpus` and emitted by `LiveAudioFixturePreparationTest` from the pinned Kokoro test model. `review-soft.wav` is the documented `volume=0.6` transform of `review.wav`; no user/physical recordings are included. The speech-classifier test uses their PCM with the bundled Silero model and does not load an ASR model or download anything at test runtime.

Fixture SHA-256:

- `continue.wav`: `33275cc6981150f6657a98e9e4440cb8fb6ca0b7b1a2e79802bbb1ce7894e017`
- `review-soft.wav`: `4fbb8386b6d5b374dae764b7256451ef4c724b968184d37adc7c7b5500fc3cd7`
- `review.wav`: `1f1eeab1efbd4029ad3a742ec969974a7bbb7c92e2865e75658462f16847e807`
