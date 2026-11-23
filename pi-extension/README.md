# Pi operator extension

With the daemon running, install dependencies with `bun install --frozen-lockfile` in this directory and start Pi with `pi --extension /absolute/path/to/pi-extension`. Put the native `kompi` executable on PATH or set `KOMPI_BIN` to its absolute path.

- `/kompi pair [--token] [name]` displays a QR code or copyable pairing token. Keep it private.
- `/kompi status` checks the local service.
- `/kompi devices list` lists paired devices.
- `/kompi devices revoke <full-endpoint-id>` asks for confirmation before revoking access.

The extension also provides `android_send_notification` for sending to one explicitly selected paired endpoint. Queue acceptance is not a delivery receipt.
