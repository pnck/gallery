# ADR-0004 · OneDrive provenance via base64-encoded filenames + folder README

- **Status**: Memo (2026-08) — recorded ahead of implementation; OneDrive sign-in
  (T-103) is NOT built yet, so nothing here is in code.
- **Context**: ADR-0002-era plan + the Drive-side provenance design (2026-08,
  `appProperties.sourcePath`).

## Decision (memo)

Google Drive offers `appProperties` for arbitrary per-app metadata (ADR: commit
"backup: one folder ever, provenance on every upload"). **Microsoft Graph's
driveItem has no equivalent** — no custom-property facet exists, so the
Drive-style provenance channel is unavailable on OneDrive.

The fallback, per the owner's decision: **encode the full file metadata as a
base64 object and use it AS THE FILENAME** on OneDrive uploads (e.g.
`<base64url({sourcePath, mediaStoreId, dateTakenMs, originalName})>.jpg`).
The filename is the one metadata channel every provider guarantees, survives
re-listing, and requires no special read-back support.

Because mangled filenames are user-visible in OneDrive's UI, the app must also
drop a **README into the backup folder at folder-creation time** explaining the
scheme ("filenames in this folder are base64-encoded metadata; the original
name is inside the payload — do not rename by hand").

## Consequences (for T-103)

- `ICloudStorageProvider.uploadFile(sourceProperties)` maps to Drive
  `appProperties`; the OneDrive implementation maps the same map into the
  base64url filename payload instead. Restore reads it back by decoding the
  name — `CloudFile.sourcePath`/`name` are extracted from the decoded object.
- A collision-avoidance suffix (content hash prefix) should live INSIDE the
  payload, not appended to the filename, to keep decoding unambiguous.
- The README is written once per folder creation (never overwritten), so user
  edits to it are preserved.
