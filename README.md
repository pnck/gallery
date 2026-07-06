# BYOS Gallery

**Bring Your Own Storage Gallery** — a zero-middleware, dual-cloud smart photo gallery for Android.
Your photos sync silently to **your own** Google Drive / OneDrive; free up local space with one tap;
previews open instantly in any state. The app never holds your photos — the data is entirely yours.

> 📄 Full product & technical spec: [docs/BYOS-Gallery-PRD.md](docs/BYOS-Gallery-PRD.md) (PRD v1.1)
> 📄 Transport layer & cross-platform design: [docs/BYOS-Transport-Layer-Design.md](docs/BYOS-Transport-Layer-Design.md)

## Architecture

Clean Architecture, five Gradle modules (PRD §2.2). `:core-domain` + `:core-network` + `:core-provider`
form the **reusable virtual backend** — pure-Kotlin where possible, KMP-portable by design.

```
:app           Compose UI · MVI · Hilt · Paging 3 · Coil 3        (Android)
:core-domain   domain models · repository contracts               (pure Kotlin)
:core-data     Room · MediaStore scanner · WorkManager workers    (Android)
:core-provider ICloudStorageProvider · Drive/OneDrive · AppAuth   (minimal Android)
:core-network  shared OkHttp · ApiResult · transport insertion    (pure Kotlin)
```

Key invariants (do not break — see [AGENTS.md](AGENTS.md)):

- **No self-hosted server.** Google Drive REST v3 / Microsoft Graph are the backend.
- **Strict three-layer models**: network DTO → Room entity → UI domain model; the UI only sees `TimelinePhoto`.
- **Four-state sync machine** (`PENDING_UPLOAD / SYNCED / CLOUD_ONLY / PENDING_DELETE`) is the source of truth.
- **Transport is an insertion layer**: acceleration off ⇒ byte-for-byte identical to never having it. No `VpnService`.

## Tech stack

Kotlin · Jetpack Compose (BOM 2026.04.01) · MVI · Hilt · Room · Paging 3 · WorkManager ·
Retrofit/OkHttp (HTTP/2) · Moshi · AppAuth (no GMS) · Coil 3 · Telephoto ·
(EPIC-5) userspace WireGuard: Rust boringtun + smoltcp via UniFFI/Gobley

## Requirements

- JDK **17+** (21 recommended — CI uses 21)
- Android SDK: `platforms;android-36`, latest `platform-tools` / `build-tools`
- Android Studio (current stable) or plain CLI
- min SDK 26 · target SDK 35 · compile SDK 36

## Build

```bash
./gradlew build            # assemble + unit tests + lint (what CI runs)
./gradlew :app:assembleDebug
./gradlew test             # JVM unit tests only
```

First build downloads Gradle 8.14.3 via the wrapper (checksum-pinned).
Point `local.properties` at your SDK: `sdk.dir=/path/to/android-sdk`.

### OAuth client setup

OAuth client IDs are **not** committed. Create your own:

- Google Cloud Console → OAuth client (Android) → scope `drive.file`
- Microsoft Entra admin center → app registration → `Files.ReadWrite offline_access User.Read`

Redirect scheme is `io.github.pnck.gallery` (see `appAuthRedirectScheme` in `app/build.gradle.kts`).

## Roadmap (PRD §13)

| Milestone | Scope |
|---|---|
| **M1** 骨架打通 | `:core-provider` + AuthManager (Google) + Room (T-101/T-201) |
| **M2** 单云盘闭环 | Drive upload/list + scanner + UploadWorker + timeline (T-102/T-202/T-301/T-402) |
| **M3** 预览与释放 | Coil auth interceptor + viewer + scoped-storage cleanup (T-401/T-403/T-302) |
| **M4** 双云盘 + 增量 | OneDrive provider + delta sync + debounce (T-103/T-303/T-304) |
| **M5** 打磨 | permission matrix, NFR perf, dedup, settings (T-104/T-203) |
| **EPIC-5** (parallel) | in-app userspace WireGuard + SOCKS acceleration chain (T-501…T-505) |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). AI-assisted development conventions live in [AGENTS.md](AGENTS.md).

## License

TBD — not yet decided by the project owner.
