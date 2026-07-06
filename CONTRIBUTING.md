# Contributing

## Workflow

- Branch from `main`: `feat/T-102-drive-provider`, `fix/…`, `chore/…` — reference the WBS task ID
  from PRD §11 where one exists.
- Commit style: [Conventional Commits](https://www.conventionalcommits.org) (`feat:`, `fix:`, `refactor:`,
  `docs:`, `test:`, `chore:`). Keep subjects ≤ 72 chars.
- Every PR must pass CI (`./gradlew build`) and fill in the PR template, including the acceptance
  criteria of the task it implements.

## Ground rules (from the PRD — violations are review blockers)

1. **Dependency direction**: `:app → :core-data / :core-provider → :core-network`, `:core-domain` at the
   bottom. Core modules never depend on `:app`. `:core-domain` / `:core-network` stay Android-free.
2. **Model separation** (PRD §3): DTOs stay inside `:core-provider`; entities stay inside `:core-data`;
   the UI consumes only domain models.
3. **`ApiResult` everywhere** (PRD §3.3): provider/repository boundaries never throw raw exceptions.
4. **State machine first** (PRD §3.7): any feature that moves a photo between states must be expressed
   as a documented transition; update the transition table if you add one.
5. **Security** (PRD §8.4.6, §5.2): tokens & WG keys only in EncryptedSharedPreferences; no tokens,
   short-lived thumbnail URLs, or secrets in logs or in Room beyond the single cached thumbnail URL.
6. **Scoped storage** (PRD §7.3): never delete media silently; always `MediaStore.createDeleteRequest`.
7. **No `VpnService`**, no WebView OAuth (Google rejects it), no HTTP/3 (SOCKS chain is TCP-only).

## Local checks before pushing

```bash
./gradlew build          # unit tests + lint, same as CI
```

## Where things go

| You are adding… | Module |
|---|---|
| A screen / ViewModel / DI wiring | `:app` |
| A domain model or repository contract | `:core-domain` |
| Room schema, scanner, workers, repository impls | `:core-data` |
| Cloud API DTOs, provider drivers, auth | `:core-provider` |
| HTTP client, interceptors, transport | `:core-network` |

Room schema changes: bump `@Database(version)`, provide a `Migration`, and commit the generated
JSON under `core-data/schemas/`.
