# AGENTS.md — AI collaboration guide

Guidance for AI coding agents (Claude Code, Copilot, etc.) working in this repo.
Humans: read CONTRIBUTING.md first; this file adds machine-relevant detail.

## What this project is

BYOS Gallery: an Android photo gallery whose *only* backend is the user's own
Google Drive / OneDrive. There is **no server to write**. The "backend" is a
reusable client-side abstraction (`:core-provider` + `:core-network` + `:core-domain`).

Authoritative specs — **read the relevant section before implementing**:

- `docs/BYOS-Gallery-PRD.md` — product spec, data models, contracts, WBS tasks (T-xxx), acceptance criteria
- `docs/BYOS-Transport-Layer-Design.md` — transport/acceleration layer, KMP portability rules

When a request conflicts with the PRD, say so and ask — don't silently deviate.

## Build & verify

```bash
./gradlew build                 # full check: assemble + unit tests + Android Lint (= CI)
./gradlew test                  # JVM unit tests only (fast)
./gradlew :core-network:test    # single module
```

- JDK 17+ required (CI uses 21). Android SDK: compileSdk 36.
- There is no emulator in CI; don't add instrumented tests without discussing where they run.

## Module map & dependency rules (hard constraints)

```
:app          → :core-data, :core-provider, :core-network, :core-domain   (Android, Compose+Hilt)
:core-data    → :core-domain, :core-provider                              (Android: Room/MediaStore/WorkManager)
:core-provider→ :core-network                                             (minimal Android: AppAuth Context)
:core-network → (nothing internal)                                        (pure Kotlin JVM)
:core-domain  → (nothing internal)                                        (pure Kotlin JVM)
```

- Never add an Android dependency to `:core-domain` or `:core-network` — they must stay
  JVM-pure (future KMP `commonMain`).
- Hilt/DI wiring lives in `:app/di` only. Core modules use plain constructor injection.
- The UI may only consume `TimelinePhoto` (domain). DTOs (`provider/dto`) and Room entities
  never cross their module boundary.

## Invariants the PRD treats as law

1. **ApiResult, not exceptions** (PRD §3.3): every provider method returns
   `ApiResult<T>`; `retryable=true` maps to WorkManager `Result.retry()`.
2. **Four-state machine** (PRD §3.7): `PENDING_UPLOAD(0) → SYNCED(1) → CLOUD_ONLY(2) → PENDING_DELETE(3)`.
   Codes are persisted — changing them is a Room migration. Follow the transition table.
3. **Hashes are provider-specific** (PRD §3.5): Drive=MD5, OneDrive=quickXor/sha1.
   Never dedup across providers by hash; hashes are computed lazily at upload, never at scan.
4. **Incremental sync ≠ page tokens** (PRD §4.3): downstream sync uses Drive Changes API /
   Graph delta. `nextPageToken`/`nextLink` are pagination only.
5. **Thumbnail auth split** (PRD §8.3): Drive thumbnails need `Authorization: Bearer`;
   OneDrive thumbnail URLs are pre-authorized — adding a header breaks them (400).
6. **Secrets** (PRD §5.2, §8.4.6): tokens/AuthState/WG keys → EncryptedSharedPreferences only.
   Never log them, never store them in Room. Thumbnail URLs: cache at most the latest one.
7. **Scoped storage** (PRD §7.3): local deletions go through `MediaStore.createDeleteRequest`
   from the UI. Workers must not attempt `File.delete()`.
8. **Insertion layer** (Transport Design §3.0, G5): the gallery kernel has zero compile-time
   dependency on transport. Transport off must equal "never integrated" byte-for-byte —
   `SharedHttpClientTest` pins this; keep it green.
9. **Forbidden**: Android `VpnService`, WebView OAuth (`disallowed_useragent`), HTTP/3/QUIC,
   absolute file paths for media (content:// URIs only), photos written to DCIM from the app.

10. **One OkHttpClient** (PRD §8.1): Retrofit, Coil and AppAuth's ConnectionBuilder share the
    singleton from `SharedHttpClient` / `AppModule`. Don't construct ad-hoc clients.

## Conventions

- Kotlin official style, 4-space indent, 120-col lines, trailing commas (see .editorconfig).
- KDoc on public contracts should cite the PRD section it implements (existing files show the pattern).
- Compose discipline (PRD §2.4): immutable models, stable lazy keys, explicit image sizes for Coil.
- Skeleton methods use `TODO("T-xxx: …")` referencing the WBS task; keep that convention.
- Version catalog only (`gradle/libs.versions.toml`) — no inline dependency strings.
  Versions were verified against Google Maven / Maven Central; Compose BOM 2026.04.01,
  Coil 3.5.x and Telephoto 0.19.x are pinned by PRD §2.3.

## Current status (bootstrap, 2026-07)

Skeleton only: contracts, Room schema v1, DTOs, Retrofit surfaces, Compose shell with an
empty timeline. Provider drivers (`GoogleDriveProvider` / `OneDriveProvider`), AuthManager
impl, workers and RemoteMediator are `TODO(T-xxx)` stubs. Milestone order: PRD §13 (M1→M5;
EPIC-5 transport is a parallel track). Open decisions D1–D9 / D-T2…D-T4 are listed in the
PRD §12 and Transport Design §10 — ask the owner before resolving one in code.
