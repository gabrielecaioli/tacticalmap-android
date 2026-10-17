# TacticalMap

An Android app for real-time tracking of a team in the field. It shows the position and status of every team member on a tactical map that also works offline. To save battery, it adapts how often it uses GPS. It syncs data over Bluetooth between nearby devices and through Firebase when a network is available.

> See the [project presentation](docs/TacticalMap.pdf) for design details.

## Features

- **Adaptive location policy.** A central `PolicyEngine` chooses the GPS interval and accuracy from the current activity, battery level and status:
  | Condition | GPS interval |
  |---|---|
  | Emergency (`DANGER`) | 10 s, high accuracy |
  | Running | 10 s |
  | Walking | 30 s |
  | Still | 2 min |
  | Battery < 30% | base interval doubled, balanced power |
- **Activity recognition.** Uses Google Play Services Activity Recognition to detect still, walking and running.
- **Emergency / SOS mode.** You can trigger SOS from the UI or with a triple tap on the device, which uses accelerometer jerk detection. The app also raises it automatically after a period of immobility. Vibration confirms the change, and SOS markers pulse on the map.
- **Data slimming.** `DataSlimmer` transmits only significant updates: emergencies, activity changes, or real movement. It discards redundant points to save energy and bandwidth.
- **Offline Bluetooth mesh:**
  - BLE advertising and scanning to discover peers, with power modes driven by the policy.
  - Bluetooth Classic P2P connections between paired devices.
  - Delta synchronization of tracking records with deduplication. Records from other peers are relayed onward, and emergencies are relayed first.
- **Cloud sync.** Uses the Firebase Realtime Database. Records are stored locally first in Room and uploaded when the device is online. Teammates' positions are received live.
- **Offline tactical maps.** The app shows OpenStreetMap tiles with osmdroid. A WorkManager job downloads the tiles of the area of operations (AO) into a local SQLite archive.
- **Foreground service.** `MissionForegroundService` keeps tracking, gesture detection and sync running while the screen is off.

## Tech stack

- **Language:** Kotlin. UI is built with Android Views plus Jetpack Compose (Material 3).
- **AndroidX:** Navigation, Lifecycle/ViewModel, Room (with KSP), WorkManager.
- **Maps and location:**
  - osmdroid for OpenStreetMap maps.
  - Google Play Services Location (Fused Location Provider and Activity Recognition).
- **Cloud:** Firebase Realtime Database.
- **Tests:** JUnit 4, MockK, kotlinx-coroutines-test.
- **Build:**
  - Gradle (Kotlin DSL), with a version catalog in `gradle/libs.versions.toml`.
  - AGP 9.2 and JDK 21 toolchain.
  - minSdk 24, target/compile SDK 36.

## Project structure

```
app/src/main/java/com/example/myapplication/
├── MainActivity.kt, BaseApplication.kt
├── data/local/          # Room database, DAO, entities, repository, cloud sync
├── logic/
│   ├── policy/          # PolicyEngine, LocationTracker, DataSlimmer, battery & activity monitoring
│   ├── mesh/            # BLE discovery, Bluetooth P2P connections, delta sync, deduplication
│   └── map/             # Offline tile download (WorkManager)
├── services/            # MissionForegroundService
├── ui/
│   ├── fragment/        # Configuration and map fragments
│   ├── map/             # Compose tactical map screen, map picker, MapViewModel
│   └── db/              # Database debug screen
└── utils/               # Tap detection, vibration, map cache helpers

app/src/test/            # Unit tests (PolicyEngine, DataSlimmer, DeltaSyncHandler)
```

## Getting started

### Requirements

- Android Studio, recent enough to support AGP 9.2.
- JDK 21. Gradle can provision it automatically through the foojay toolchain resolver.
- A physical Android device, which is recommended because GPS, activity recognition and Bluetooth are needed. Mesh sync requires at least two **paired** devices.

### Firebase setup

The Firebase configuration file is **not** included in the repository. To build the app:

1. Create a project in the [Firebase console](https://console.firebase.google.com/) and enable the **Realtime Database**.
2. Add an Android app with package name `com.example.myapplication`.
3. Download `google-services.json` and place it in `app/google-services.json`.
4. The Realtime Database URL is currently hardcoded in the source code (search for `firebasedatabase.app`). Replace it with your own database URL.

### Build and run

Open the project in Android Studio and run the `app` configuration. You can also use the command line:

```bash
./gradlew assembleDebug
```

```bash
./gradlew test
```

When the app starts, enter a **Mission ID** and a **User ID**. All devices in the same team must use the same Mission ID. Grant the requested permissions, set the map center and start the mission.

## Permissions

| Permission | Purpose |
|---|---|
| Fine / coarse location | Position tracking |
| Activity recognition | Detect still / walking / running |
| Bluetooth (scan, connect, admin) | Peer discovery and mesh sync |
| Foreground service (location, data sync) | Background tracking and map download |
| Post notifications | Service and download notifications |
| Vibrate, wake lock | SOS feedback, keep sensors active |
| Internet, network state | Firebase sync and map tiles |

## Authors

- Klaudio Ciacia
- Gabriele Caioli
- Matteo Halilaga
- Lorenzo Vanni
