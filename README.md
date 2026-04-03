# Orchard Tracker

An Android app for tagging GPS coordinates in the field. Designed for use on a tractor or while walking an orchard — large, high-contrast buttons that are easy to tap with gloves on.

## Features

- **One-tap GPS tagging** — six large buttons each save your current location with a label
- **Live GPS status** — color-coded accuracy indicator (green ≤ 5 m, amber ≤ 15 m, red > 15 m)
- **Map view** — all saved points displayed on a map with a color-coded legend; switch between street map (OpenStreetMap) and satellite imagery (ESRI World Imagery) via the overflow menu
- **My Location** — tap the location button on the map to center on your current GPS position
- **Delete points** — tap any marker to delete it individually, or use "Select Area" to drag a rectangle and delete all points within it
- **Filter by tag** — use "Filter Tags" in the map menu to show only selected tag types; filters by tag name so reassigned slots don't cause collisions
- **Custom labels** — rename any button to suit your operation (changes persist across sessions)
- **In-app updates** — check for new versions from the overflow menu; downloads and installs directly
- **CSV export** — share your tagged points via email, Drive, or any other app
- **Screen stays on** — display never sleeps while the app is open
- **Haptic feedback** — short vibration confirms every saved point without looking at the screen
- **Offline map tiles** — download street map or satellite tiles for any area so the map works without cell service
- **No account or API key required**

### Default button labels

| Button | Default label |
|---|---|
| 🟢 | Gopher |
| 🔴 | Blight |
| 🟠 | Blackberry |
| 🔵 | Broken Irrigation |
| 🟣 | Prune |
| 🟡 | End Tank |

All labels can be renamed via **⋮ → Edit Labels** from the main screen.

---

## Download

**[Download the latest APK (v1.5.0)](https://github.com/ctdenney/OrchardTracker/releases/download/v1.5.0/app-release-signed.apk)**

On your Android device, open the downloaded file and follow the prompts. If prompted, enable *Install from unknown sources* in Settings → Security.

---

## Requirements

- Android **8.0 (Oreo) or newer** (API 26+)
- GPS / location permission
- Internet connection for map tiles (map markers and saved data work fully offline)

---

## Installation

### Option A — Build with Android Studio (recommended)

1. **Install Android Studio** — download from [developer.android.com/studio](https://developer.android.com/studio)
2. **Clone the repository**
   ```bash
   git clone https://github.com/ctdenney/OrchardTracker.git
   ```
3. **Open the project** — in Android Studio choose *File → Open* and select the `OrchardTracker` folder
4. **Let Gradle sync** — Android Studio will download all dependencies automatically (requires an internet connection)
5. **Connect your phone** — enable *USB Debugging* on the device:
   - Go to *Settings → About Phone* and tap **Build Number** seven times to unlock Developer Options
   - Go to *Settings → Developer Options* and enable **USB Debugging**
   - Connect via USB and accept the prompt on the phone
6. **Run the app** — press the green **▶ Run** button in Android Studio

### Option B — Build from the command line

1. **Install prerequisites**
   - [JDK 17](https://adoptium.net/) or newer
   - Android SDK (install via Android Studio or the standalone SDK tools)
   - Set the `ANDROID_HOME` environment variable to your SDK path

2. **Clone the repository**
   ```bash
   git clone https://github.com/ctdenney/OrchardTracker.git
   cd OrchardTracker
   ```

3. **Generate the Gradle wrapper** (first time only)
   ```bash
   gradle wrapper --gradle-version=8.4
   ```

4. **Build a debug APK**
   ```bash
   ./gradlew assembleDebug
   ```
   The APK will be output to:
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

5. **Install on a connected device**
   ```bash
   ./gradlew installDebug
   ```
   Or copy the APK to your phone and open it (you may need to allow *Install from unknown sources* in your device settings).

---

## Permissions

| Permission | Why it's needed |
|---|---|
| `ACCESS_FINE_LOCATION` | Records precise GPS coordinates when a button is pressed |
| `ACCESS_COARSE_LOCATION` | Fallback if fine location is unavailable |
| `INTERNET` | Downloads map tiles from OpenStreetMap / ESRI |
| `VIBRATE` | Haptic confirmation when a point is saved |

---

## Usage

### Recording a point
1. Open the app and wait for the GPS indicator to turn amber or green
2. Tap any of the six tag buttons — the point is saved instantly with a timestamp
3. A toast notification and short vibration confirm the save

### Viewing saved points
- Tap **View Map** at the bottom of the main screen
- The map centers on all your recorded points
- Tap any marker to see its label, timestamp, and accuracy — tap **Delete** in that dialog to remove it
- Use the **Legend** overlay (bottom-left of the map) to see which color is which label
- Tap the **location button** (bottom-right of the map) to center on your current GPS position
- Tap **⋮ → Switch to Satellite** to view ESRI World Imagery instead of the street map (tap again to switch back)
- Tap **⋮ → Filter Tags** to show only selected tag types on the map

### Deleting points by area
1. Tap **Select Area** in the bottom bar — the button turns orange
2. Drag a rectangle over the map to select the points you want to remove
3. Confirm the deletion in the dialog that appears
4. Tap **Cancel** at any time to exit selection mode without deleting

### Renaming buttons
1. Tap **⋮** (top-right overflow menu) → **Edit Labels**
2. Type a new name for any button — leave blank to restore the default
3. Tap **Save Labels**

### Downloading offline map tiles
1. Tap **View Map** → **⋮ → Download Offline Map**
2. Select the tile source — **Street Map (OSM)** or **Satellite (ESRI)**
3. Pan and zoom the map to the area you want to cache
4. Adjust the **Zoom** range (8 = regional overview, 13 = individual fields, 18 = row-level detail)
5. Check the tile / size estimate, then tap **Download Area**
6. A progress bar tracks the download — the app can be used normally once it finishes

> **Tip:** Zoom levels 13–17 cover most orchard work and typically download in under a minute on a good connection. Wider zoom ranges increase download size exponentially. Download both tile sources separately if you want to switch between them offline.

To free up storage, tap **Clear Cache** on the same screen.

### Exporting data
1. Tap **View Map** → **Export CSV**
2. Choose a share target (email, Google Drive, etc.)

The CSV format is:
```
id,tag,latitude,longitude,altitude_m,accuracy_m,timestamp
```

---

## Tech stack

| Component | Library |
|---|---|
| Language | Kotlin |
| Build | Gradle 8.4 / AGP 8.2 |
| UI | Material Components for Android |
| Database | Room (SQLite) |
| Location | Google Play Services — FusedLocationProviderClient |
| Maps | OSMDroid — OpenStreetMap street tiles + ESRI World Imagery satellite tiles (no API key) |
| Async | Kotlin Coroutines |

---

## License

MIT
