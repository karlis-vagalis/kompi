# Wire contract

[kompi-protocol](../rust/kompi-protocol/src/lib.rs) defines the versioned network messages, constraints, and local worker and control envelopes. Their JSON Schemas are in [schema/](schema/). Network frames contain a four-byte big-endian length and a bounded CBOR map; JSON Schema describes the decoded data, not the wire encoding.

From this directory run `bun run generate` to export the schemas and regenerate TypeScript and Kotlin clients.
