# Pi worker

The Rust daemon starts one Bun worker per active phone conversation. The worker runs the installed Pi CLI in RPC mode under the daemon account; its tools, environment and permissions apply to requests from paired phones.

Build from the repository root:

```sh
(cd pi-extension && bun install --frozen-lockfile)
(cd daemon && bun install --frozen-lockfile && bun run build)
cargo build --release --locked -p kompi-daemon
KOMPI_SDK_WORKER_DIR="$PWD/daemon" target/release/kompi serve
```

For a packaged install, put the compiled worker in `worker/` beside the executable. This directory needs `package.json`, `bun.lock`, the full `dist-worker/` tree and production dependencies (`bun install --production --frozen-lockfile`). Keep Bun and Pi on the service PATH. Linux also needs util-linux flock. Set `KOMPI_SDK_RUNTIME` to use a specific Bun executable. On Windows, install Pi with npm and keep Node.js 22.19+ on PATH. Kompi resolves the installed `pi.cmd` shim and launches its JavaScript entry point without a shell. To override discovery, set `KOMPI_PI_CLI` to the absolute path of the installed `pi.cmd` or its `.js`, `.mjs` or `.cjs` entry point. On Linux, use an absolute Pi executable path.
