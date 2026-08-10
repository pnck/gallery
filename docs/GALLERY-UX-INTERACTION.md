# Gallery Design Language

The product's complete UX design — vision, foundations, motion, gesture
choreography, components, and per-surface specs. Opinionated by design; bounded by
the owner's guardrails (Appendix A). References: Google Photos, Aves, Fossify
Gallery, Material 3 (Expressive), adapted to a backup-first product.

---

## 0. Product character

**The wall is the product; backup is quiet infrastructure.**

Three feelings every screen must preserve:

1. **Immediate** — the wall renders from local data instantly, always. Nothing the
   user sees first ever waits for network, account, or scheduler.
2. **Honest** — every status is derived, never asserted. A badge, a count, a banner
   says only what the data proves. When the app can't know (no account), it says so.
3. **Calm** — backup happens in the background like plumbing. One banner maximum,
   no dialogs for states, no badges shouting for attention. The loudest element on
   any screen is the user's own photos.

Vocabulary follows: photos are *backed up* (not "synced"), the queue is *waiting*,
scope is *library folders*.

## 1. Foundations

### 1.1 Grid & density
The timeline is an edge-to-edge square-crop grid. Three density steps on pinch
(72 / 100 / 148 dp cells) with a 150 ms size interpolation; the anchor photo under
the pinch centroid stays put. No headers inside the grid flow except year-month
section labels (Google-Photos style), keyed and stable.

### 1.2 Color & the badge language
Sync state is communicated by **shape, not color** (monochrome white glyphs on a
40%-black scrim circle, 15 dp, top-end corner of the cell). Color is reserved for
*system* meaning: primary = action, error = failure/degradation, surface = content.
Rationale: seven badges × five theme colors is noise; shapes read at 72 dp.

Badge set (derived, never persisted): hourglass = checking, upload-arrow = not
backed up, cloud-queue = waiting, cloud-sync = uploading, cloud-done = backed up,
cloud = cloud-only, cloud-off = excluded.

### 1.3 Typography & spacing
M3 scale, used sparsely: titleLarge for screen titles, bodyLarge for status lines,
bodyMedium for rows, labelMedium for captions/pills. 4 dp spacing rhythm; 16 dp
screen margins, 24 dp for sheet/screen headers. One emphasis level per screen.

### 1.4 Surfaces
One elevation story: content sits on `surface`; temporary containers (banner, fix
rows) use `primaryContainer` / `errorContainer` as *flush* blocks, no shadows.
Sheets use `surfaceContainerLow`. Scrim at 32% black behind anything modal.

## 2. Motion

- **Springs, not curves**, for anything finger-connected (drawer, sheet, cell
  long-press lift): critically damped, no overshoot on UI chrome; slight overshoot
  (≈0.2) only on playful scale changes (selection check).
- **Shared-axis Z** for destination changes (timeline ⇄ sync screen ⇄ settings):
  fade + 8 dp scale, 250 ms. **Shared-axis X** for pager/viewer swipes (content-led).
- **Predictive back** honored everywhere: back gesture previews the previous
  destination; inside a sheet, back collapses first, exits second.
- **State changes animate**: badge swaps crossfade 150 ms; count pills count-up on
  change; the backup banner slides in/out, never pops.
- Nothing loops. A spinner appears only while a *first* result is pending; progress
  is determinate whenever a total exists.

## 3. Gesture & scroll choreography

This section is the contract for anything that slides — the place where "里面和
外面谁滚动" is decided once, globally.

### 3.1 The one-owner rule
At every instant, exactly one layer owns the vertical drag axis. Ownership is
decided by **anchor state + scroll position + drag origin**, never by heuristics:

| Sheet anchor | Drag starts on | Owner |
|---|---|---|
| expanding (not full) | anywhere in sheet | the sheet |
| fully expanded | drag handle (top 32 dp) | the sheet |
| fully expanded | content, list **can** scroll up | the inner list |
| fully expanded | content, list at top (`canScrollBackward=false`) | the sheet (collapse hand-off) |

The hand-off is physical: when the list hits top and the finger keeps pulling, the
sheet takes the finger mid-gesture (nested-scroll connection — the list's
`canScrollBackward=false` releases the delta to the parent). No jump, no dead zone.

### 3.2 Sheets have binary intent
A sheet is either **dismissed or fully expanded**; half-expanded exists only while
the finger is on it (see Appendix A — content is never half-cut, so a resting
half-state is reserved for content that *fits* half a screen).
Release behavior: positional threshold 40% of the travel decides expand-vs-dismiss;
a fling (≥ 700 dp/s downward anywhere on the sheet, ≥ 400 dp/s on the handle)
dismisses regardless of position. The sheet tracks the finger 1:1 — velocity
inherits on release.

### 3.3 No touch-through, ever
Modal containers block the world behind them: scrim consumes all pointers (tap =
dismiss), content behind is non-focusable and non-scrollable. The system back
gesture is consumed by the topmost container only. Bottom-edge content padding ≥
gesture inset + 16 dp so the home-swipe never races a control.

### 3.4 Drawer
The reference implementation (already shipped): finger-tracked edge drag from the
leading 20 dp only, MD-width sheet, velocity-or-half release, hamburger guarded
until the destination settles. Same physics as sheets (§3.2).

## 4. Components

### 4.1 Backup banner (the one allowed interruption)
Pinned above the grid, flush `primaryContainer`, three states:
- **Running**: thumbnail of the current file, "Backing up 12 / 1853", determinate
  bar, pause icon.
- **Waiting**: "N waiting to back up" + [Back up now] text button (the queue is
  manual by design — this banner never appears from a scan alone).
- **Paused**: "Backup paused · N waiting" + [Resume].
Hidden otherwise. Never stacks with a second banner; degradations live on the
sync screen, not here.

### 4.2 Fix row
The degradation component (media access / autostart): `errorContainer`, small
body, single action button. Appears only from evidence (blind scan, failed
probe), disappears the moment the evidence clears. Lives on the Sync screen —
and once per app version may also surface as the banner when backup is provably
broken (never both at once).

### 4.3 Count pills
Four quiet numbers (Waiting / Not backed up / Backed up / Cloud only),
headlineSmall + labelMedium. Read-only; the screen's actions sit below them.

### 4.4 Folder row
Checkbox, folder **path** (`DCIM/Camera/`), photo count, and the source hint
(tencent paths say QQ/WeChat in the path itself). 56 dp touch target, the whole
row toggles — never a tiny checkbox hitbox.

### 4.5 Fast scroller
Right-edge scrubber: year-month bubble follows the thumb, haptic tick per year
boundary (API <34; SegmentTick after), invisible until the grid flings.

## 5. Surfaces

### 5.1 Timeline (Browse)
`[≡] Photos ⟳? [☑] [⋮]` — the title is inert; the overflow carries Sort & filter /
Back up now / — / Sync / Storage / Settings (grouped, Appendix A.3).
Empty library: one centered line + illustration, never a fake photo state; first
scan in flight: a spinner instead (honest about "checking").
Grid photos carry §1.2 badges; "checking" collapses to "not backed up" when no
account is connected (classification can't happen — say the true thing).

### 5.2 Selection
The contextual bar replaces the top bar with a slight lift:
`[✕] N selected [⬆☁] [🗑] [⋮]`.
Cells dim + check (scale-spring on the check). Long-press enters and selects; tap
toggles; the count sits in a centered pill under the bar (the bar holds actions,
not text). Exit on ✕, back, or empty selection.

### 5.3 Sync screen (full screen, ex-sheet)
A `Sync` destination. Top: the honest status line (Idle/Scanning/Uploading/
Failed/Not-connected, colored by severity). Fix rows next (media, autostart).
Counts (§4.3). Queue (each chain: name + state). Actions at the bottom as
full-width tonal buttons: Back up now · Rebuild · Clear queue (only when N>0).
This is the app's instrument panel — density is fine here; it scrolls.

### 5.4 Library folders (Settings child)
One scrolling screen: a one-line purpose header ("Shown in your gallery and
included in backup"), an "All folders" master row, then §4.4 rows. Scope is
durable configuration — it surfaces in the settings row summary, NOT on the
wall. On-wall indicators are reserved for *temporary* view state: an active
sync-state filter (≠ All) gets a small chip that opens view options; the folder
scope never does.

### 5.5 Settings
A single column of labelled rows + one-line subtitles, grouped: Account · Library
folders · Backup folder · Storage · Transport · My Drive access · About.
No icon-only rows; the tunnel is a row with a state subtitle.

### 5.6 Viewer
Immersive; chrome fades on tap. Top: back, share, delete, ⋮ (info, save to
device, free up space). Info is the one remaining sheet (short by nature):
full-expand, drag handle, §3.1 scroll rules. Zoom: double-tap 2.5× anchored on
the tap point, pinch beyond; pan settles with spring.

### 5.7 Sign-in
The device flow as a full-screen moment: the code in displayLarge, one action
(open browser), live status. No WebView, no dialogs.

## 6. States

| State | Presentation |
|---|---|
| First scan | Spinner, never a fake "no photos" |
| Empty library | One line + illustration |
| No account | "Not connected — photos stay on this device" (grey, not error) |
| Offline | Wall unchanged; banner hides; sync actions report on tap, not before |
| Failed chain | Red status line on the Sync screen + banner offers Retry |
| Degraded permission | Fix row (§4.2) — evidence-driven only |

## 7. Migration

P0: folders → Settings screen + timeline chip; sheet policy (handle, full-expand,
one-owner) applied to every remaining sheet; selection bar to 2 icons + overflow.
P1: Sync screen replaces the status sheet; My Drive details full-screen.
P2: motion pass (badge crossfade, count-up, predictive back), info-sheet polish.

## 8. Refactoring plan (detailed)

Scope basis: 4 screens totalling ~2.5k lines (`TimelineScreen` 1155, `MyDriveScreen`
518, `PhotoDetailScreen` 412, `SettingsScreen` 400), 8 `ModalBottomSheet` call
sites. Effort in ideal dev-days (d) for one engineer; each task ships green
(build + unit tests + lint) and is verified on the MI 9 (MIUI) for gesture work.

### P0 — interaction debt (no behavior changes beyond layout)

| # | Task (doc §) | Changes & files | Effort |
|---|---|---|---|
| T1 | **Library folders screen** (§5.4) | New `ui/settings/folders/LibraryFoldersScreen.kt` (route + Scaffold + single LazyColumn: purpose header, All-folders master row, §4.4 folder rows); reuse `refreshBuckets`/`setScanFolders` (moved into a small `LibraryFoldersViewModel`); Settings entry row with scope summary; timeline **"Filtered" chip** (visible iff `scanBuckets` non-empty, taps through); delete `FoldersSheet` + its row in `ViewOptionsSheet`; strings. Risk: low — pure move; cursor-reset logic already lives in `setScanFolders`. | 1.5 d |
| T2 | **Sheet policy pass** (§3, §6) | All remaining sheets: `rememberModalBottomSheetState(skipPartiallyExpanded = true)` where content can overflow, explicit drag handle, gesture-inset bottom padding, verify the one-owner hand-off on MIUI (M3's internal nested-scroll covers the list-top → sheet hand-off once always-expanded; only add a custom `NestedScrollConnection` if verification shows a dead zone). Files: `TimelineScreen.kt` (view options, status interim), `PhotoDetailScreen.kt` (info). | 0.5–1 d |
| T3 | **Selection bar → 2 icons + overflow** (§4/§5.2) | `SelectionAppBar`: keep Back up + Delete icons; Free up space / Save to device / Select all become overflow text items. File: `TimelineScreen.kt`. | 0.25 d |

**P0 total ≈ 2.5–3 d.**

### P1 — destination upgrades

| # | Task (doc §) | Changes & files | Effort |
|---|---|---|---|
| T4 | **Sync screen** (§5.3) | New `ui/sync/SyncScreen.kt` route: move status line, fix rows, count pills, queue, action buttons out of `SyncStatusSheet` into a Scaffold with back; overflow "Sync status" navigates instead of opening a sheet; delete the sheet. Most composables move verbatim — the work is framing + navigation + re-verifying the fix rows. | 1 d |
| T5 | **My Drive details → full screen** (§5.6 today) | Details panel becomes a destination (row set scrolls); `MyDriveScreen.kt` + nav. | 0.5 d |
| T6 | **Settings regrouping** (§5.5) | Group labelled rows (Account / Library / Backup / Storage / Transport / About) with one-line subtitles; no new logic. `SettingsScreen.kt`. | 0.5 d |

**P1 total ≈ 2 d.**

### P2 — polish

| # | Task (doc §) | Changes & files | Effort |
|---|---|---|---|
| T7 | **Motion pass** (§2) | Badge crossfade + selection check spring; banner slide-in/out; count-pill count-up; predictive-back opt-in + verify on API 29 MIUI (falls back gracefully). Touches `TimelineScreen`, `PhotoDetailScreen`, theme/motion tokens. | 1 d |
| T8 | **Viewer zoom & info sheet polish** (§5.6) | Double-tap anchor + pan settle (telephoto config), info-sheet drag/scroll verification. | 0.5 d |

**P2 total ≈ 1.5 d.**

### Estimate

| Phase | Effort | Note |
|---|---|---|
| P0 | 2.5–3 d | biggest single item is T1 (new screen + nav + chip) |
| P1 | 2 d | mostly moving composables into destinations |
| P2 | 1.5 d | motion is cheap to add, slow to *tune* — timeboxed |
| **Total** | **≈ 6 d (range 4.5–8)** | one engineer, CI-green per task, MIUI device verification included |

Risks that could stretch the range: (a) the sheet/list hand-off on MIUI needing a
custom nested-scroll connection (T2, +0.5 d); (b) predictive-back quirks on the
API-29 test device (T7, +0.5 d); (c) nav-destination scaffolding cost if the
drawer/nav guards need rework for the two new routes (T1/T4, +0.5 d).

Explicitly **out of scope**: new features (search, albums, video), any sync/backup
behavior change, OneDrive, and anything the owner hasn't approved in §0–§6.

**Deferred requirements to fold into the 0.2.x refactor** (noted by the owner,
alpha.104): there is currently NO way to force a full rescan — the Sync screen
(§5.3) should gain a "Rescan library" action (full sweep), placed next to
Rebuild; interaction TBD in P1.

### T9 · Wall-integrity realignment (SHIPPED for 0.1.x — full-diff projection)

The owner's model: the wall shows each local photo exactly once, always; cloud
knowledge merges in; cloud-only appends. Every wall-integrity bug (double rows,
phantoms, cursor desync) traced to one root: the Room projection was synchronized
*incrementally* (a scan cursor), and partial synchronization always desyncs.

Options evaluated (implementation choice is ours; the outcome is the owner's):

- **A′ — full-diff projection (CHOSEN, 0.1.x)**: the projection is RE-DERIVED
  wholesale on every scan — insert new, refresh changed, delete local-backed rows
  whose file vanished. No cursor at all. Guards: blind-scan veto on the delete
  side only (<5% of known rows returned → apply inserts/updates, skip deletes);
  allowlist-scoped deletion; process-wide scan mutex; UNIQUE(localUri) +
  INSERT OR IGNORE as integrity constraints. After every scan the projection IS
  MediaStore — duplicates and ghosts self-heal on the next scan, by construction.
  Cost: a sub-second metadata query per trigger, unchanged from before.
- **B — no persisted local list (deferred)**: wall = live MediaStore stream
  left-joined with cloud-knowledge tables; Room holds links/queue/sessions only.
  Cleanest end state but rewrites the repo/pager/counts/upload-queue path
  (2–3 d) — disproportionate for the 0.1.x line. Revisit if A′ ever shows a
  structural crack; the visible outcomes are identical.

0.2.x remains the UI revamp; T9 is 0.1.x stabilization, shipped.

---


## Appendix A · Owner guardrails (binding)

1. Icons only for universal metaphors; app-specific concepts get text.
2. ≤ 3 actions per toolbar; overflow is one flat menu.
3. Menus: flat, ordered, grouped, destructive last.
4. Containers behave like physical objects: finger-tracked, visible handle, one
   scroll owner, zero touch-through; system gestures win at edges.
5. Content is never shown half-cut.
