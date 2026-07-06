package io.github.pnck.gallery.domain

/**
 * The four-state sync machine — the real Source of Truth of the gallery (PRD §3.7).
 *
 * Transitions are driven by the scanner, the upload/cleanup/delete workers and
 * downstream (delta) sync. See the transition table in the PRD before adding states.
 */
enum class SyncState(val code: Int) {
    /** Local file exists, nothing in the cloud yet: waiting for upload. */
    PENDING_UPLOAD(0),

    /** Present both locally and in the cloud. */
    SYNCED(1),

    /** Local copy released ("free up space"); only the cloud copy remains. */
    CLOUD_ONLY(2),

    /** User requested deletion; row is removed once the cloud (± local) delete completes. */
    PENDING_DELETE(3),
    ;

    companion object {
        fun fromCode(code: Int): SyncState =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown SyncState code: $code")
    }
}
