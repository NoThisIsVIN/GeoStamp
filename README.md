# GeoStamp Camera 📸📍

**GeoStamp Camera** is a production-grade, privacy-first Android camera app built with modern **Jetpack Compose**, **CameraX**, and **Material 3**. It burns real-time GPS coordinates, reverse-geocoded address, altitude, accuracy, compass heading, date/time, and an interactive **Satellite or Street map thumbnail** directly onto captured photos and writes standard EXIF GPS tags.

**Zero Ads. Zero Analytics. Zero Trackers. Zero In-App Purchases.**



## 🚀 Key Features

### 1. CameraX Engine & OEM Hardware Extensions
- **OEM Vendor Extensions:** Integrates `androidx.camera:camera-extensions` via `ExtensionsManager`. On supported hardware (such as **Nothing OS** and **OnePlus OxygenOS**), automatically unlocks native hardware **Auto HDR** and scene optimization.
- **Vibrant Color Profile:** Proprietary image enhancement pipeline that applies +25% saturation lift and +10% contrast curve to prevent the flat, washed-out tones common with raw Quad-Bayer sensor streams.
- **Controls:** Tap-to-Focus with animated reticle, Pinch-to-Zoom (up to 10×), front/back camera switching with un-mirrored text stamping, Flash (Off/Auto/On), Rule-of-Thirds Grid, Aspect Ratio (4:3, 16:9, 1:1), Countdown Timer (Off/3s/10s), and Hardware Volume Shutter.

### 2. High-Precision Location & Sensors
- **Fused Location Provider:** High-accuracy GPS updates (1–2s updates while active, automatically stopped when backgrounded to prevent battery drain).
- **Fallback:** Seamless fallback to Android `LocationManager` (GPS + Network providers).
- **Status Chip:** Real-time feedback (`Acquiring GPS…`, `GPS locked (±5m)`, `Last known location`, `No GPS`).
- **Offline Reverse Geocoding:** Reverse geocodes using Android's built-in `Geocoder` with a 25-meter movement threshold.
- **Compass:** High-accuracy `TYPE_ROTATION_VECTOR` sensor smoothed with a low-pass filter and compensated for display rotation.
- **Live Weather:** Free, keyless temperature and weather condition retrieval via Open-Meteo API.

### 3. Satellite & Street Map Thumbnails
- **Satellite Map (Default):** High-resolution satellite tiles powered by **Esri World Imagery** stitched into a clean 256×256 square around your location with a centered pin and drop shadow.
- **Street Map:** Standard OpenStreetMap tiles.
- **Offline Resilience:** Automatically renders an offline vector radar grid with coordinates when no network connection is available.
- **Local Disk Cache:** Tiles cached up to 50 MB with LRU pruning.

### 4. Native Gallery & Scoped Storage Integration
- **Direct `DCIM/Camera` Storage:** Saved photos immediately appear in the primary timeline of **Google Photos** and the device's native Gallery app (Nothing OS Gallery / OnePlus Gallery).
- **Immediate Media Scanning:** Calls `MediaScannerConnection.scanFile` upon save so third-party gallery apps index the photo without delay.
- **In-App Gallery:** Built-in viewer with zoom/pan, system delete requests (`MediaStore.createDeleteRequest` on Android 11+), Android share sheet (`ACTION_SEND`), and "Open in Maps" (`geo:lat,lng`).

### 5. Standard EXIF Stamping
- Writes standard EXIF tags:
  - `TAG_GPS_LATITUDE` & `TAG_GPS_LONGITUDE`
  - `TAG_GPS_ALTITUDE`
  - `TAG_GPS_TIMESTAMP` & `TAG_GPS_DATESTAMP`
  - `TAG_ORIENTATION = NORMAL`
  - `TAG_MAKE` & `TAG_MODEL`
  - `TAG_DATETIME`, `TAG_DATETIME_ORIGINAL`, `TAG_DATETIME_DIGITIZED`
- Option to save an additional unstamped original copy.

---

## 📱 Platform & Device Compatibility

- **Primary Tested Platforms:** OnePlus 13 / 13R (ColorOS 17 / OxygenOS, Android 15–16) and Nothing Phone (2a) (Nothing OS 2.x/3.x, Android 14–16).
- **minSdk:** 26 (Android 8.0 Oreo)
- **targetSdk:** 35 (Android 15)
- **compileSdk:** 35
- **16 KB Page-Size Ready:** Built with Android Gradle Plugin 8.7.3, Kotlin 2.0.21, and Gradle 8.11.1.
- **Edge-to-Edge:** Native window insets and camera cutout handling with safe area padding.

---

## 🛠️ Building & Releasing

### Prerequisites
- JDK 17 or JDK 21 (Temurin / JBR)
- Android SDK with Platform 35 and Build-Tools 35.0.0

### Build Commands

```bash
# Build Debug APK
./gradlew assembleDebug

# Build Release APK (Signed, R8 Minified)
./gradlew assembleRelease

# Run Unit Tests
./gradlew testDebugUnitTest
```

### APK Output Paths
- **Release APK (Signed, R8 Minified):** `app/build/outputs/apk/release/app-release.apk`
- **Debug APK:** `app/build/outputs/apk/debug/app-debug.apk`

---

## 📄 License
This project is licensed under the Apache 2.0 License - see the LICENSE file for details.
