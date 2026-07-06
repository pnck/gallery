# CLAUDE.md

@AGENTS.md

Claude-specific notes:

- Local toolchain may be absent (this repo is often edited inside a proxied container).
  If `java`/`gradle` are unavailable, don't fake build results — say the build was not run
  and rely on CI (`.github/workflows/ci.yml`).
- Design docs in `docs/` are the source of truth; cite PRD/Transport-Design sections
  (e.g. "PRD §4.3") in code comments and PR descriptions.
