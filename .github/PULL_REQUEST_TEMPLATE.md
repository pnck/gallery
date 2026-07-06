## What

<!-- What does this PR do? Link the WBS task (e.g. T-102) or issue. -->

## Why

<!-- Context / PRD section reference (e.g. PRD §4.3). -->

## Checklist

- [ ] Follows the module dependency rules (see AGENTS.md — no UI → DTO/Entity leaks)
- [ ] Provider methods return `ApiResult`, no raw exceptions cross the boundary
- [ ] No tokens / thumbnail URLs persisted beyond what PRD §8.3 allows
- [ ] `./gradlew build` passes locally (unit tests + lint)
- [ ] Acceptance criteria of the WBS task are met (PRD §11)
