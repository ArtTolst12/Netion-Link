# Netion Link

**English** · [Русский](README_RU.md)

Share a host device's network connection over a local HTTP or SOCKS5 proxy — without root.

**Version 3.1.8** · Android 7.0+ · [Downloads](https://github.com/ArtTolst12/Netion-Link/releases)

## How it works

Android tethering does not normally forward connected devices through the host phone's VPN. Netion Link provides an application-level proxy instead:

- **Server:** runs HTTP/HTTPS CONNECT and SOCKS5 listeners on the host phone. Outgoing connections follow the host's routing configuration, including an external VPN when it routes this app's traffic.
- **Client:** uses Android's VPN interface to forward supported IPv4 TCP traffic and DNS requests to the host proxy. Other devices can use the proxy directly through compatible applications or proxy settings.

Netion Link does not provide VPN servers, proxy subscriptions or a hosted backend. You configure the host's internet connection and any external VPN yourself.

## Features

- Local connections over a Wi-Fi hotspot, shared Wi-Fi network or USB tethering, where the devices can reach each other.
- HTTP forwarding, HTTPS tunnels through CONNECT, and SOCKS5 TCP connections.
- Built-in Android VPN client with an explicit HTTP/SOCKS5 protocol selection.
- QR pairing with the server address, ports and connection protocol.
- Automatic port reallocation when a requested port is unavailable.
- Traffic counters, connection status and an English diagnostic log with a copyable report.
- Foreground services and power-management locks for background operation, subject to Android and device restrictions.
- Interface translations in Russian, English, German, French, Spanish, Portuguese, Polish, Chinese, Japanese and Korean.

## Getting started

Install an available APK from [Releases](https://github.com/ArtTolst12/Netion-Link/releases), or [build from source](#build-from-source). A source tag does not necessarily have a published APK.

### Android → Android

**On the host phone:**

1. Connect to the internet. If you want to share an external VPN, connect it first and make sure Netion Link is not excluded from its routing.
2. Enable a password-protected Wi-Fi hotspot, or connect both devices to a trusted local network. USB tethering can also be used when the client supports it.
3. Open the **Server** tab and start sharing.
4. Check the displayed server address and active ports. Open the pairing QR code.

**On the client phone:**

1. Connect to the host's hotspot or the same local network.
2. Open the **Client** tab and scan the QR code, or enter the server address, port and protocol manually.
3. Start the VPN connection and approve Android's VPN permission prompt.

Android normally allows only one active VPN per user/profile. The built-in client can replace another VPN on the client phone; the host's external VPN runs on the separate host device.

### Computer or another proxy-capable device

1. Connect to the host's hotspot or local network.
2. In your application or operating system, configure an **HTTP proxy** or **SOCKS5 proxy**, as supported by that client.
3. Use the address and active port shown on the server. Default ports are **8080** for HTTP and **1080** for SOCKS5, but they can change if unavailable.

For example, Windows **Settings → Network & internet → Proxy → Manual proxy setup** configures an HTTP proxy. SOCKS5 requires a client with SOCKS5 support. Proxy settings do not automatically route every application's traffic.

## Supported traffic and limitations

| Capability | Current behavior |
|---|---|
| IPv4 TCP | Forwarded by the built-in VPN client through HTTP CONNECT or SOCKS5 |
| DNS | DNS handling is built into the VPN client; configured proxy mode does not silently fall back to direct DNS |
| Arbitrary UDP / QUIC | Not forwarded; selecting SOCKS5 does not enable UDP ASSOCIATE |
| IPv6 | Not supported by the built-in VPN client |
| IPv4 fragments | Dropped; reassembly is not implemented |
| External VPN enforcement | Depends on host routing; Netion Link does not provide a kill switch for the host VPN |

Games, voice/video calls and applications that depend on unsupported traffic may not work. Compatibility and background uptime depend on the device, Android version, network and external VPN configuration.

## Security and privacy

- Use only a **trusted, access-controlled LAN or hotspot**. The proxy has no client authentication or TLS protection of its own. Do not expose its listening ports to the internet.
- HTTPS remains encrypted between the client application and destination, but the proxy does not add encryption for otherwise unencrypted application traffic.
- The app has no advertising, third-party tracking or automatic diagnostic uploads. Network connections are made to the configured proxy and destination services, not a Netion-operated backend.
- Diagnostic logs are bounded and kept in memory. Copied reports can contain proxy/destination addresses and device information; review them before sharing.
- If the host VPN disconnects or excludes Netion Link, the host's routing may use a direct connection. Check the external VPN's routing and blocking settings rather than assuming a kill switch is present.

## Troubleshooting

Check the server's **current** address, port and protocol first. Port reallocation, local-network isolation, network changes and background restrictions can prevent a connection even when both apps are running.

`Connection reset by peer` means a TCP connection was reset while communicating with the proxy. A client log alone cannot establish whether the cause is the server, network or upstream VPN. Capture diagnostic reports from **both devices** around the same time. Version 3.1.8 distinguishes connection phases, resets and deadlines; it does not guarantee that external resets cannot occur.

Do not disable socket protection, TLS verification or add a direct routing fallback to hide a connection error.

## Build from source

### Requirements

- **JDK 25**, selected by `gradle/gradle-daemon-jvm.properties`.
- **Android SDK Platform 37** and **Build Tools 36.0.0**, with the SDK licenses accepted.
- Android Studio compatible with **Android Gradle Plugin 9.4.1**, or Android SDK command-line tools.
- The included **Gradle 9.6.0 wrapper**. A separate Gradle installation is not required.

Java source/bytecode compatibility is set to 11; this is separate from the JDK used to run Gradle. The minimum device API is 24 and the target API is 37.

```bash
git clone https://github.com/ArtTolst12/Netion-Link.git
cd Netion-Link
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On Windows PowerShell, replace `./gradlew` with `.\gradlew.bat`; in Command Prompt, use `gradlew.bat`. If an archive does not preserve executable permissions on Linux/macOS, run `chmod +x gradlew` first.

Set the Android SDK path in Android Studio or your untracked `local.properties` (`sdk.dir=...`). The first build needs internet access to download uncached Gradle, JDK and dependencies.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

For a distributable build, configure your own release signing key through **Build → Generate Signed Bundle / APK…**. `assembleRelease` alone does not configure a release signature. Keep private keys and passwords out of Git, and preserve the existing signing key if you need to update an already installed build.

### Verification

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Unit tests use local fixtures, including loopback TCP servers. They do not replace testing Android VPN permissions, foreground services, network switching, camera access, clipboard behavior or layouts on real devices.

## Libraries and licenses

| Component | Version | License |
|---|---|---|
| Jetpack Compose | BOM 2026.02.01 | Apache 2.0 |
| AndroidX Activity / Core / Lifecycle | 1.13.0 / 1.19.1 / 2.11.0 | Apache 2.0 |
| CameraX | 1.4.1 | Apache 2.0; embedded libyuv has BSD notices |
| ZXing Core | 3.5.3 | Apache 2.0 |
| Kotlin Coroutines | 1.9.0 | Apache 2.0 |

Resolved transitive runtime modules are listed in [RUNTIME_LICENSE_INVENTORY.md](RUNTIME_LICENSE_INVENTORY.md). It is a metadata inventory, not a complete audit of embedded third-party source or a substitute for upstream copyright/NOTICE files. License texts are also included in `app/src/main/assets/licenses/`.

JUnit 4.13.2 is a **test-only** dependency under EPL-1.0, not AGPL-relicensed code. JUnit/Hamcrest are not application runtime dependencies. AndroidX instrumented tests use `androidTestImplementation` and a separate test APK. Test tools retain their own licenses; distributing test APKs or vendored tools requires checking their applicable terms.

## License

Netion Link is distributed under the **GNU Affero General Public License v3.0 (AGPL-3.0)**. See [LICENSE](LICENSE).

Third-party components retain their respective licenses and notices. The software is provided without warranty, as described in the license. Users are responsible for their configuration, use of third-party services and compliance with applicable requirements.
