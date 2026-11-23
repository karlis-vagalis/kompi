# Kompi

**Your personal Pi assistant on Android. Send a message or talk in Live mode.**

There are already good ways to run a coding agent from a phone. I built Kompi (*Pi companion*) because I wanted an assistant I could talk to without turning every request into a coding task. Type a quick message, or switch to Live mode for a spoken conversation. Pi runs on your paired computer, so when you *do* want to work on code, you can pick up an existing session with its files and tools.

**Demo gallery.** Android emulator screenshots with a fictional conversation and simulated paired computers.

| Light | Dark |
| --- | --- |
| [![Light chat: Pi suggests lemony chickpeas and explains how to pack leftovers.](docs/screenshots/chat-light.png)](docs/screenshots/chat-light.png) | [![Dark chat: the same meal-planning conversation.](docs/screenshots/chat-dark.png)](docs/screenshots/chat-dark.png) |
| [![Light Computers screen: Desk computer selected, Travel laptop also paired.](docs/screenshots/computers-light.png)](docs/screenshots/computers-light.png) | [![Dark Computers screen showing the same two trusted computers.](docs/screenshots/computers-dark.png)](docs/screenshots/computers-dark.png) |

> **Development status:** Kompi is under very active development. Expect breaking changes between versions. Use it at your own risk.

## First chat

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.kompi.app%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fkarlis-vagalis%2Fkompi%22%2C%22author%22%3A%22karlis-vagalis%22%2C%22name%22%3A%22Kompi%22%7D"><img src="docs/images/obtainium.png" width="161" alt="Add Kompi to Obtainium for Android updates"></a>

1. On your phone, add Kompi to Obtainium above or [download the Android APK](https://github.com/karlis-vagalis/kompi/releases/latest/download/kompi.apk) directly. Open the APK to install it. If Android asks, allow installation from the app you opened it with.
2. On your x86-64 computer, install Pi, Node.js 22.19+ and Bun (Linux also needs `flock`). Install the daemon for your platform:

   **GNU Linux**
   ```sh
   curl -fL https://github.com/karlis-vagalis/kompi/releases/latest/download/kompi-daemon-installer.sh -o kompi-daemon-installer.sh && sh kompi-daemon-installer.sh
   ```

   **Windows PowerShell**
   ```powershell
   Invoke-WebRequest https://github.com/karlis-vagalis/kompi/releases/latest/download/kompi-daemon-installer.ps1 -OutFile kompi-daemon-installer.ps1 -ErrorAction Stop
   .\kompi-daemon-installer.ps1
   ```

   If your system policy doesn't allow the installer, download the [Linux archive](https://github.com/karlis-vagalis/kompi/releases/latest/download/kompi-daemon-x86_64-unknown-linux-gnu.tar.gz) or [Windows ZIP](https://github.com/karlis-vagalis/kompi/releases/latest/download/kompi-daemon-x86_64-pc-windows-msvc.zip) instead. Extract into a dedicated directory and use the full path to the extracted executable in place of `kompi` below.

3. Open a new terminal so the installed command is on your PATH. Prepare the matching worker dependencies, then start the daemon:

   **GNU Linux**
   ```sh
   worker=$(kompi setup-worker) && (cd "$worker" && bun install --production --frozen-lockfile) && kompi serve
   ```

   **Windows PowerShell**
   ```powershell
   $worker = kompi setup-worker
   if ($LASTEXITCODE -ne 0) { throw "Worker setup failed" }
   Push-Location -LiteralPath $worker
   try { bun install --production --frozen-lockfile; if ($LASTEXITCODE -ne 0) { throw "Dependency install failed" } } finally { Pop-Location }
   kompi serve
   ```

4. In another terminal, run `kompi pair`. On the phone, open **Computers** and scan the code. Send “Hello” to try it.

Prefer to build locally? See the [host](daemon/README.md) and [Android](android/README.md) instructions.

**Access and privacy:** A paired phone can run Pi as your host user. Keep pairing codes private. Pi stores transcripts on the host; the phone caches chat data, and Pi sends requests to your configured model provider. To remove access, find the phone with `kompi devices list` and run `kompi devices revoke <endpoint>`.

**Upgrading:** Check the release notes for breaking changes and update the app and daemon together. Stop the daemon, rerun the installer and repeat the worker setup before restarting.

**Why use Pi directly?** Pi Durable might be a better fit for session management, but I wanted one Pi setup to maintain. Kompi runs the same Pi CLI I use in the terminal, so both use the same settings, credentials, and extensions.

Kompi is licensed under [AGPL-3.0-only](LICENSE). Third-party components retain their own licenses.

Run `just` to list the [development recipes](justfile) · [Contributing](CONTRIBUTING.md) · [Release details](docs/releasing.md)
