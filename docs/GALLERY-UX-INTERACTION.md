# Gallery UX & Interaction Model

Reference for how the app's screens, app bars, menus and modes should be organized.
Derived from the interaction conventions shared by well-regarded open-source galleries
— **Aves** (deckerst/aves), **Fossify Gallery** (ex Simple Gallery), and the de-facto
standard **Google Photos** — adapted to our backup-first product (see the priority
`backup > sync > album`).

The goal of this doc: stop cramming every action into the top bar as loose icons, stop
using the title as a menu, and give each screen a predictable structure.

---

## 1. The three modes (every gallery has these)

| Mode | What it is | App bar |
|------|-----------|---------|
| **Browse** | The grid of photos (our timeline). | Normal top app bar. |
| **Selection** | Entered by long-press or a Select action; multi-select for batch actions. | Contextual app bar (count + batch actions), replaces the normal bar. |
| **Viewer** | Full-screen single photo (our detail). | Immersive; chrome toggles on tap. |

These map to what we already have. The problem is the **Browse** app bar is overloaded.

## 2. Top app bar rules (the core fix)

The reference apps follow a strict hierarchy — **at most 2–3 primary icons, everything
else under a single overflow "⋮" menu**:

```
[≡ nav]   Title            [primary action] [primary action] [⋮ overflow]
```

- **Navigation icon** (left): opens the side drawer (our My Photos / My Drive / …).
- **Title**: a plain label ("Photos"). **Never a clickable menu.** Transient status
  (scanning / uploading N) is shown as a small inline progress indicator or a subtitle,
  NOT by hijacking the title.
- **Primary actions** (max ~2): the most frequent, e.g. *Select*, and *Search* if present.
- **Overflow "⋮"**: everything else as a text menu — sort/filter, sync now, sync status,
  storage, settings. Text labels are self-describing (no guessing what an icon means).

Google Photos: bottom nav (Photos/Search/Library) + a small avatar; per-screen actions
minimal. Aves/Fossify: a search bar up top, a sort/group control, and a "⋮" for the rest.
Selection replaces the whole bar with a contextual one.

## 3. Selection (contextual) app bar

Entered by long-press on an item OR a *Select* action. Replaces the normal bar:

```
[✕ close]   "N selected"          [action] [action] [⋮ more]
```

- Left: close (exit selection). Title: the count.
- Show only the **most common batch actions** as icons (ours: Back up, Free up space,
  Delete); push the rest (Save to device, Share, Select-all) into the "⋮".
- Long-press = enter selection AND select that item; subsequent taps toggle.

## 4. Sort / filter / group

A single entry ("View options" / a sort chip) that opens a sheet — not scattered icons.
Ours already does this (the Tune sheet: sort, sync-state filter, folders). Keep it, but
reach it from the overflow menu, not a dedicated top-bar icon.

## 5. Viewer

Immersive, tap toggles chrome. Actions live in the top bar of the viewer + an info panel
(EXIF/details) as a bottom sheet. Ours matches this; leave as-is.

## 6. Side drawer / sections

Top-level destinations only (our My Photos / My Drive / reserved). Not per-screen actions.
Settings can live either in the drawer or the Browse overflow — we keep it in overflow +
reachable from the account panel.

---

## 7. Our features mapped onto this model

Current Browse (timeline) top bar is: `[≡] [title=sync-status, tap→sheet] [Tune][Select][Sync][Settings]`
— four loose icons + title-as-menu. Reorganized:

```
[≡ drawer]   Photos   ⟳(when syncing)        [☑ Select]   [⋮]
                                                            ├ Sort & filter
                                                            ├ Back up now
                                                            ├ Sync status
                                                            ├ Storage
                                                            └ Settings
```

- **Title** → plain "Photos". Sync status moves to: (a) a small progress spinner in the
  bar while scanning/uploading, and (b) a "Sync status" overflow item that opens the
  existing status sheet.
- **Select** stays as the one primary icon (most common entry to batch ops; long-press
  also works).
- **Tune / Sync / Settings** icons → overflow text items ("Sort & filter", "Back up now",
  "Settings"), plus "Sync status" and "Storage".

Selection bar keeps Back up / Free up space / Delete as icons; Save-to-device → overflow.

This is the target. Implementation lands incrementally; the Browse top-bar overflow is
step 1.
