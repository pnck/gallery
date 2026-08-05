# Gallery UX & Interaction Design

The complete interaction language of the app — principles, per-surface specs, and a
migration plan. Derived from the conventions of **Google Photos**, **Aves**, and
**Fossify Gallery**, adapted to our backup-first priority (`backup > sync > album`).

Supersedes the 2026-07 draft of the same file. Code comments citing this doc refer to
section numbers of THIS revision.

---

## 1. Design principles (non-negotiable)

**P1 — Icons must be self-evident.**
An icon-only action is allowed only when its metaphor is universal: select ☑, close ✕,
delete 🗑, share ↗, cloud-upload ⬆☁, play/pause, back ←, overflow ⋮, drawer ≡.
Anything app-specific (transport/tunnel, sync states, storage management) gets **text**
(a menu item or a labelled row), never a bare icon. The photo corner badges stay icons
— they *are* the established pattern — but the tunnel config entry must not be one.

**P2 — At most 3 actions in any toolbar.**
`[primary] [primary?] [⋮ overflow]`; overflow is a single flat text menu (§5).
If a fourth candidate appears, the weakest one moves to overflow.

**P3 — Menus are flat, ordered, grouped.**
One level only (no submenus ever), most-used first, separator between semantic groups,
destructive last. Max ~7 items before a group split is required.

**P4 — Containers must behave like physical objects.**
A drawer/sheet that slides in must (a) track the finger while dragging, (b) expose a
visible drag handle, (c) have an unambiguous scroll owner at every moment, and (d)
never let touches fall through to the content behind it ("操作穿透").
System gestures win at screen edges: nothing scrollable or dismissible may sit where
the system back-swipe (left/right edges) or home-swipe (bottom edge) lives, unless the
container consumes the gesture intentionally (a drag handle is intentional).

**P5 — Content is never shown half-cut.**
If a surface's content doesn't fit its initial frame, the surface is the wrong shape.
Scrollable or structured content (lists, status dashboards, pickers) is a **full
screen**; a sheet is for short, single-purpose content only (§6).

---

## 2. The three modes

| Mode | What it is | App bar |
|------|-----------|---------|
| **Browse** | The photo grid. | Normal top bar, ≤3 actions (P2). |
| **Selection** | Multi-select for batch actions. | Contextual bar replaces the normal bar. |
| **Viewer** | Full-screen single photo. | Immersive; chrome toggles on tap. |

## 3. Browse app bar (timeline)

```
[≡]   Photos  ⟳?            [☑ Select] [⋮]
                                          ├ Sort & filter
                                          ├ Back up now
                                          ├─────────────
                                          ├ Sync status
                                          ├ Storage
                                          └ Settings
```

- **Title** — plain text, never clickable. Transient work shows as a small inline
  spinner (`⟳`) next to it, nothing else.
- **Select** — the single primary icon (universal metaphor, P1).
- **Overflow** — flat, grouped (P3): view actions / sync actions / app actions.
- The sync-status *detail* opens from the menu item, never from the title.

## 4. Selection bar

```
[✕]   N selected          [⬆☁] [🗑] [⋮]
                                      ├ Free up space
                                      ├ Save to device
                                      └ Select all
```

- Exactly 2 primary icons (P2): **Back up** (backup-first product) and **Delete**.
- Free-up-space, Save-to-device, Select-all → overflow (text, self-describing).
- Entry: long-press an item (also selects it) or ☑; exit: ✕ or system back.

## 5. Overflow menu anatomy

Single column, three groups max, separators between groups. Text labels; an optional
leading icon only when it *reinforces* the text (never carries meaning alone, P1).

## 6. Sheet vs full screen (the bottom-sheet policy)

**Default answer for "should this be a sheet?" is NO.** Sheets are the exception.

| Use a **sheet** only when ALL true | Use a **full screen** when ANY true |
|---|---|
| Content fits without scrolling | Content scrolls (lists, dashboards) |
| One short decision/reading (info, confirm) | Structured sections / many actions |
| Loses nothing if dismissed | User may want to stay / return |

Rules for the sheets that remain:
1. `skipPartiallyExpanded = true` when content can exceed half the screen — a sheet
   either shows its content whole or it doesn't exist (P5).
2. Always a visible **drag handle**; dismiss = handle fling, scrim tap, or back.
3. **One scroll owner.** When fully expanded, the inner list owns vertical drags;
   the sheet itself moves only from the handle. (This is the folders-picker fix, §8.3.)
4. Never stack a sheet over a sheet; a second level is a full screen.
5. Bottom-sheet content must clear the system gesture bar (padding), and no
   dismissible surface may rely on bottom-edge swipes alone (P4).

Consequences for current surfaces:

| Surface | Verdict |
|---|---|
| Sort & filter | Sheet OK (short, non-scrolling) — add `skipPartiallyExpanded`. |
| Photo info | Sheet OK (fits; add drag handle + full expand). |
| Sync status | **Full screen** ("Sync" destination) — dashboard + queue + fix rows will grow. |
| Folders picker | **Full screen inside Settings** (§8.3) — scrollable multi-select list. |
| Delete/clear confirms | Stay `AlertDialog` (not a sheet at all — dialogs are for decisions). |

## 7. Drawer

The custom edge-drag drawer (finger-tracked, leading-edge-only, MD-width) is the
reference implementation of P4 — keep it. Destinations only: **Photos**, **My Drive**.
Settings is reachable from both the drawer (bottom, with ⚙ + label) and the timeline
overflow; do not add per-screen actions to the drawer.

## 8. Per-surface specs

### 8.1 Timeline (browse)
As §3. The backup banner (Google-Photos-style progress) stays pinned above the grid —
it is a *status*, not an action; its only buttons are Pause/Resume/Back-up-now (text
buttons, P1-compliant).

### 8.2 Sync status → "Sync" screen
Today: half sheet with pills + queue + up to 3 buttons + 2 warning rows — exactly the
"半幅卡片显示不完整" anti-pattern. Becomes a full-screen destination:
header status line → degradation fix rows (media access / autostart) → counts →
queue → actions (Sync now / Rebuild / Clear queue). Scrollable, back returns.

### 8.3 Folders to include → Settings → "Library folders"
**Moves out of view options.** It governs library *scope* (what the gallery shows AND
what backs up) — a data-policy setting, not a view tweak.

- Entry: Settings → "Library folders" (row with current scope summary: "All folders"
  or "N of M folders").
- Screen: header explains the effect in one sentence ("Photos in selected folders
  appear in your gallery and are included in backup"), then the checkbox list with
  folder **path + count** (already collected). No nested sheet, no nested scroll —
  the screen scrolls as one list (fixes the scroll-owner fight).
- An "IM folders" hint: folders are listed with their source app visible (QQ/WeChat
  buckets are identifiable by path), letting the user trim IM noise deliberately.
  Default stays *all folders* — silently excluding user media is worse — but a
  restricted scope shows a small "filtered" chip on the timeline so scope is never
  a hidden state.
- Persistence: the allowlist survives updates; any version that resets it is a bug
  (it lives in DataStore, outside the destructively-migrated DB — verify on upgrade).

### 8.4 Settings home
Plain rows with title + one-line subtitle (no icon-only rows): Account, Library
folders, Backup folder name, Storage, Transport, My Drive access, About.
Transport is a *row with a subtitle* ("Tunnel: connected via …"), never a bare icon.

### 8.5 Viewer (detail)
Immersive; chrome toggles on tap (already correct). Top bar: back, share, delete, ⋮
(info, save-to-device, free-up). Info stays a sheet (fits §6 rules: add drag handle,
skipPartiallyExpanded). EXIF/path rows already included (Folder, MediaStore id).

### 8.6 My Drive
Browser-style: breadcrumb top bar (back = up), file rows, ⋮ per row (details/rename?…
only what exists). Details panel → full-screen row set (it scrolls).

### 8.7 Sign-in / permissions
Platform dialogs only: the system permission prompt, MIUI editor deep-links from the
sync screen's fix rows. No explainer dialogs.

## 9. Migration plan

| Phase | Changes |
|---|---|
| **P0 (interaction debt, no new features)** | Folders picker → Settings full screen + scope chip on timeline; all remaining sheets get handle + `skipPartiallyExpanded` where content can overflow; selection bar down to 2 icons + overflow. |
| **P1** | Sync status sheet → "Sync" full-screen destination; My Drive details → full screen; settings rows re-labelled per §8.4. |
| **P2** | Viewer ⋮ grouping polish; drawer Settings row; IM-folder hint text in the folders screen. |

Each phase is independently shippable; no phase changes behavior beyond layout.
