# CLAUDE.md

@AGENTS.md

Claude-specific notes:

- **Toolchain bootstrap (dev container)**: run `. /home/node/.claude/toolchains/env.sh`
  before any Gradle command. It exports JAVA_HOME (Temurin 17), ANDROID_HOME and
  GRADLE_USER_HOME, all persisted on the `/home/node/.claude` docker volume.
- This container is **linux/aarch64**; Google ships no arm64 AAPT2. The global
  `~/.claude/gradle/gradle.properties` sets `android.aapt2FromMavenOverride` to a wrapper
  that runs the official x86_64 aapt2 through OrbStack Rosetta + a private glibc sysroot
  (`/home/node/.claude/toolchains/x86_64-sysroot`). Don't remove that override.
- If the toolchain is missing entirely, don't fake build results — say the build was not
  run and rely on CI (`.github/workflows/ci.yml`).
- `git commit` needs `--no-gpg-sign` here (the user's gitconfig enables signing but the
  container has no gpg). Never push without being asked.
- Design docs in `docs/` are the source of truth; cite PRD/Transport-Design sections
  (e.g. "PRD §4.3") in code comments and PR descriptions.
