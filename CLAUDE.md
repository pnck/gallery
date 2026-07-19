# CLAUDE.md

@AGENTS.md

Claude-specific notes:

- **Toolchain bootstrap**: run `. /opt/toolchains/env.sh` before any Gradle command.
  It exports JAVA_HOME (Temurin 17), ANDROID_HOME, GRADLE_USER_HOME and the Rust
  toolchain, all installed under `/opt/toolchains` (Rust host builds use the system
  `gcc` from apt — no self-bootstrapped compiler).
- This container is **linux/aarch64**; Google ships no arm64 AAPT2. The global
  `~/.claude/gradle/gradle.properties` sets `android.aapt2FromMavenOverride` to a wrapper
  that runs the official x86_64 aapt2 through OrbStack Rosetta + a private glibc sysroot
  (`/opt/toolchains/x86_64-sysroot`). Don't remove that override.
- If the toolchain is missing entirely, don't fake build results — say the build was not
  run and rely on CI (`.github/workflows/ci.yml`).
- `git commit` needs `--no-gpg-sign` here (the user's gitconfig enables signing but the
  container has no gpg). Never push without being asked.
- Design docs in `docs/` are the source of truth; cite PRD/Transport-Design sections
  (e.g. "PRD §4.3") in code comments and PR descriptions.
