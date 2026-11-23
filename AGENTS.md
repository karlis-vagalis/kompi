# Developing Kompi

Start with `README.md` for setup and run `just` for the list of development recipes. From a fresh checkout, `just daemon-build sdk-build` builds the host components; `just apk` builds the Android debug app. Building does not pair devices or start a service.

## Where changes belong

- `android/` owns the phone UI, local state, and Android side of the transport.
- `rust/kompi-daemon/` owns host identity, pairing, transport, and session lifecycle. `daemon/` is the worker that talks to Pi over RPC, not a second native daemon. Keep Pi's normal configuration discovery; do not introduce a separate set of model or tool settings.
- `rust/kompi-protocol/` and `protocol/` define the shared wire contract. After a contract change, run `just protocol-generate`, inspect the generated diff, and check both peers. Avoid hand-editing generated clients.
- `pi-extension/` exposes Kompi commands inside Pi. Run `just extension-typecheck` for extension changes.

## Checking a change

Run the relevant tests and build the component you changed. For Rust, use `just rust-test` or a focused `cargo test --locked -p <crate>`. For a pairing change, use a disposable emulator and test host: receiving the paired response on Android proves more than generating a code. For session handoff, verify that the first writer releases the transcript before the next one takes ownership.

Use `just devices` and pass an explicit serial to `just install` and `just launch`. Never select a contributor's phone automatically. Keep pairing codes, identities, credentials, signing keys, local databases, and real conversation content out of fixtures, screenshots, and commits.
