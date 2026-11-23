# Interoperability fixtures

From the repository root:

```sh
cargo run --locked -p kompi-protocol --example export-wire-vectors > protocol/test-vectors/rust-v4.json
(cd pi-extension && bun install --frozen-lockfile && bun test/generate-vectors.ts)
```

`rust-v4.json` contains JSON and CBOR serialized by Rust from the same typed envelopes. The TypeScript decoder reads those bytes. The TypeScript generator uses the logical messages (not their Rust hex) to encode `v4.json`, including length-prefixed frames and deliberately malformed inputs. Rust's `typescript_wire` integration test and Android's `WireVectorsTest` consume that output independently. CI also regenerates and compares the artifacts on a fresh checkout.
