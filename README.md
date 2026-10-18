# Netion Link

**English** | [Русский](README_RU.md)

---

Share VPN and proxy connections between devices without Root permissions.

### About

Normally, when enabling Wi-Fi Hotspot on Android, tethered devices bypass the active VPN and connect directly to the mobile network.

Netion Link solves this issue:
1. **On the host device (Server):** runs a local HTTP and SOCKS5 proxy server that routes traffic through the phone's active VPN tunnel.
2. **On the client device (Client):** utilizes a built-in TUN VPN service to intercept supported system traffic and redirect it to the host proxy without requiring complex manual proxy configuration.

### Original purpose, disclaimer and limitations

Netion Link was initially created for the developer's personal use on mobile networks in the Russian Federation that apply allowlists (often called “white lists”). Its intended use is to route traffic from a connected device through a primary VPN that the user has already configured and connected on the host device.

**Netion Link does not provide VPN servers, proxy servers outside the user's devices, or a VPN subscription.** Users must provide and configure their own upstream VPN and are responsible for how they use the app and any third-party services. The project is provided “as is”, without a guarantee that it will work with every carrier, network, VPN provider, destination, or configuration. The author and contributors are not responsible for service interruptions, blocked connections, or the availability and policies of third-party networks and services.

The app was developed first for a personal use case, not as a guaranteed high-availability service. **Stability during long-running sessions is not guaranteed.** Network operators may block or change access to VPNs, proxies, or particular protocols. Support for arbitrary UDP traffic and IPv6 is limited in the current implementation.

### Features

- **Tethering over any interface:** Wi-Fi Hotspot, shared Wi-Fi local network, or USB tethering.
- **Dual Proxy Modes:**
  - **HTTP CONNECT** — ideal for web browsing, websites, and social media.
  - **SOCKS5** — for apps that support SOCKS5. Compatibility with games, calls, and other UDP-based traffic depends on the traffic type and may be limited.
- **Built-in VPN Client:** connect any Android device in one tap without Root permissions.
- **QR Code Pairing:** generate a QR code on the server and scan it instantly with the client camera.
- **Smart Port Selection:** automatically suggests free ports if default ones (8080 or 1080) are occupied.
- **Traffic Monitoring:** real-time display of uploaded/downloaded data and peak connection speed.
- **Diagnostics Log:** technical network log for troubleshooting and monitoring connection events.
- **Multi-language Support:** full localization for 10 languages (Russian, English, German, French, Spanish, Portuguese, Polish, Chinese, Japanese, Korean) with automatic system language detection.
- **Background Stability:** maintains connection integrity when the screen is off or locked using foreground services and system locks.

### Installation

1. Navigate to the [Releases](https://github.com/ArtTolst12/Netion-Link/releases) section.
2. Download the latest release APK (`Netion_Link_V2.3.15.apk`).
3. Install the APK on your Android device (allow installation from unknown sources if prompted).

### Usage Guide

#### Option 1. Android to Android Sharing
1. **On the host device:**
   - Ensure internet connectivity and connect to your primary VPN.
   - Enable Wi-Fi Hotspot.
   - Open Netion Link, go to the "Server" tab, and tap "Start Sharing".
   - Tap "Show QR Code".
2. **On the client device:**
   - Connect to the host device's Wi-Fi network.
   - Open Netion Link, go to the "Client" tab, and tap "Scan QR Code".
   - Scan the host screen, tap "Connect to VPN", and accept the Android VPN prompt.

#### Option 2. Connecting from PC (Windows / macOS / Linux)
1. Connect your computer to the phone's Wi-Fi hotspot.
2. Open proxy settings on your PC:
   - On Windows: Settings -> Network & internet -> Proxy -> Manual proxy setup.
3. Enter the phone's IP address (shown on the Server tab, e.g., `192.168.43.1`) and port `8080` (HTTP) or `1080` (SOCKS5).

### Building from Source

Requirements:
- Android Studio (Ladybug or newer)
- JDK 17 or JDK 21
- Android SDK (API 34 / 35, minimum Android version 7.0 — API 24)

1. Clone the repository:
   ```bash
   git clone https://github.com/ArtTolst12/Netion-Link.git
   ```
2. Open the project folder in Android Studio.
3. Wait for Gradle sync to complete.
4. Build the release APK via:
   **Build -> Generate Signed Bundle / APK...** (or run `./gradlew assembleRelease` in terminal).

### Security & Privacy

- Netion Link contains no third-party trackers, advertisements, or telemetry.
- Netion Link does not operate its own servers or backend infrastructure.
- The application runs directly on user devices; network traffic is transmitted over the local connection to the server device and then forwarded through the user-configured VPN or proxy to destination resources.

### License

Distributed under the **GNU Affero General Public License v3.0 (AGPL-3.0)**.
See [LICENSE](LICENSE) for details.

---

## Source Code

Official GitHub repository:  
https://github.com/ArtTolst12/Netion-Link
