# CarrotNavi (Tmap & Openpilot Integration)

CarrotNavi is an Android application that uses the **Tmap API** to collect route and safe driving data (e.g., speed cameras, speed limits) in real time in the background. It then broadcasts this data to Openpilot devices via **UDP communication**.

## 🚀 Key Features

- **Background Service & Communication**
  - Continuously collects Tmap data and sends UDP packets (Port 7706) in the background, even when the screen is off or other apps are in use.
- **Real-time Safe Driving Data Sync**
  - Caches and seamlessly provides camera and road speed limit data (such as `nRoadLimitSpeed` and `nSdiSpeedLimit`) based on Tmap SDK's `observableEDCData`.
- **Full Landscape Mode Support**
  - Provides an intuitive UI by separating portrait and landscape layouts, optimized for in-vehicle display environments like Android Auto.
- **Map Touch Blocking Overlay**
  - Blocks touch events on the Tmap map area to prevent accidental misoperations while driving, ensuring safe data visualization.
- **Easy Configuration & OTA Updates**
  - Easily input your Tmap App Key and UDP Target IP in the settings screen; they are auto-saved for the next launch.
  - Supports an integrated OTA (Over-The-Air) Auto-Updater that fetches the latest APK from GitHub Releases on startup.
  - Fully supports modern Android location permission policies (including the "Allow all the time" option for background execution).

## 📁 Project Structure

This repository follows the standard Android project structure:
- `app/`: CarrotNavi main application source code (Kotlin).
- `docs/`: Markdown guides regarding Openpilot integration and Tmap implementation details.
- `gradle/`: Gradle wrapper and build configuration files.

## ⚙️ Build and Installation

1. **Clone the Repository**
   ```bash
   git clone https://github.com/GRT47/CarrotNavi.git
   ```
2. **Open in Android Studio**
   - Open the cloned directory using Android Studio.
3. **Build and Run**
   - Click the `Run 'app'` button in the toolbar to install it on a device or emulator.
4. **Issue a Tmap App Key**
   - To use the Tmap API, you must acquire a Tmap App Key from the [SK Open API Portal](https://openapi.sk.com/) and enter it on the app's initial screen.

## 📡 Data Integration (UDP Broadcast)

Once running, the app broadcasts driving information in JSON format at a 300ms interval (~3.3Hz) to the specified Target IP (default `255.255.255.255`) on port `7706`. The receiving end (e.g., Openpilot or a Python script) can parse this JSON to display speed limit UIs or implement control logic.

---

*This project was created to contribute to the GRT47 and Openpilot user communities.*
