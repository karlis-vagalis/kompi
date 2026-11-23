# Interoperability fixtures

`v4.json` contains synthetic envelopes, CBOR payloads and length-prefixed frames, plus malformed payloads that readers must reject. Regenerate from `pi-extension/` with `bun test/generate-vectors.ts`. Run `bun run test` there to check framing and decoding. Kotlin vectors can be checked by setting `KOTLIN_VECTORS` to a corpus with the same format.
