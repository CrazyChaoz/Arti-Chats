# Arti-Chats

P2P chat over Tor — a privacy-first proof-of-concept that runs an embedded Tor client in the Rust backend and exposes functionality to a Kotlin Android frontend.

## What this repository contains

- `backend/` — Rust code, network & Tor integration (uses `arti`).
- `frontend/` — Android app in Kotlin, UI with Jetpack Compose.
- `flake.nix` — preferred reproducible build/dev environment for the whole project.
- `LICENSE` — project license (EUPL v1.2).

## Goals

- Route peer-to-peer messaging over Tor circuits.
- Keep networking and Tor integration in Rust for portability.
- Provide a native Android UI that talks to the Rust library.

## Requirements

- Nix with flakes enabled (preferred).
- For manual builds:
  - Rust toolchain (via rustup) for the backend.
  - Android Studio or Android SDK/Gradle and a JDK for the frontend.

## Preferred (reproducible) workflow — using `flake.nix`

The repository provides a Nix flake that builds the Android APK(s) and sets up a development shell.

- Enter a development shell:
```
nix develop
```

- Build the unsigned APK:
```
nix build
```

- Build the signed APK (uses the repo test keystore):
```
nix build .#signed-apk
```

Notes:
- Replace `x86_64-linux` with the flake’s system output you target if you modify the flake.
- The flake also exposes `ffi` and native libraries used by the frontend as `.#packages.x86_64-linux.ffi`.

## Manual build (non-Nix)

Backend (Rust)
```
cd backend
cargo build
# run a binary (check Cargo.toml for binaries)
cargo run --bin <binary-name>
```

Frontend (Android)
```
# From the repo root or inside frontend/
# Recommended: open in Android Studio
./gradlew :app:assembleDebug
./gradlew :app:installDebug    # requires device or emulator
```

When building manually you must ensure the native Rust libraries and uniffi bindings are copied into the Android project (the flake automates this step).

## License

This project is licensed under the European Union Public Licence (EUPL) v1.2. See `LICENSE` at the project root for the full text.
