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

1. On your phone, download `kompi.apk` from the [GitHub Releases page](https://github.com/karlis-vagalis/kompi/releases). Open the APK to install it. If Android asks, allow installation from the app you opened it with.
2. Download the matching x86-64 daemon from the same release: `kompi-daemon-x86_64-unknown-linux-gnu.tar.gz` for Linux or `kompi-daemon-x86_64-pc-windows-msvc.zip` for native Windows. Extract it into a dedicated directory. Install Pi, Node.js 22.19+ and Bun (`flock` is also required on Linux). In the extracted `worker` directory run `bun install --production --frozen-lockfile`. Return to the directory containing the executable and run `./kompi serve` (PowerShell: `.\kompi.exe serve`).
3. In another terminal in that directory, run `./kompi pair`. On the phone, open **Computers** and scan the code to pair. Kompi uses [iroh](https://www.iroh.computer/) for the encrypted connection between your phone and computer, connecting directly when possible or through a relay when needed. Send “Hello” to try it.

Prefer to build locally? See the [host](daemon/README.md) and [Android](android/README.md) instructions.

**Access and privacy:** A paired phone can run Pi as your host user. Keep pairing codes private. Pi stores transcripts on the host; the phone caches chat data, and Pi sends requests to your configured model provider. To remove access, find the phone with `./kompi devices list` and run `./kompi devices revoke <endpoint>`.

**Upgrading:** Check the release notes for breaking changes and update the app and daemon together.

**Why use Pi directly?** Pi Durable might be a better fit for session management, but I wanted one Pi setup to maintain. Kompi runs the same Pi CLI I use in the terminal, so both use the same settings, credentials, and extensions.

Kompi is licensed under [AGPL-3.0-only](LICENSE). Third-party components retain their own licenses.

Run `just` to list the [development recipes](justfile) · [Contributing](CONTRIBUTING.md) · [Release details](docs/releasing.md)
