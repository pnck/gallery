package io.github.pnck.gallery.data.sync

import android.util.Log
import androidx.room.withTransaction
import io.github.pnck.gallery.data.db.GalleryDatabase
import io.github.pnck.gallery.data.settings.AppSettingsStore
import io.github.pnck.gallery.provider.ICloudStorageProvider
import java.io.File
import kotlinx.coroutines.flow.first

private const val TAG = "gallery-sync"

/**
 * Account isolation for on-device staged data (review finding: signing in as a
 * DIFFERENT account used to leave the previous account's data in place, so the
 * two users' state could cross-contaminate).
 *
 * Everything wiped here is account-DERIVED, never user-authored, and — per the
 * DB's own design contract ([GalleryDatabase.create]) — re-derives from the two
 * truths (MediaStore scan + the new account's cloud listing) on the next sync:
 *  - Room `photos` rows: cloudIds / sync badges / hashes of the old account's
 *    Drive (queued flags go too — a queue is intent aimed at a specific backup
 *    target; silently retargeting it at the new account would be worse);
 *  - `sync_keys`: Drive Changes API cursors are account+folder specific;
 *  - `upload_sessions`: resumable-upload URIs are minted under the old grant;
 *  - `cacheDir/originals`: downloaded originals of the old account's files —
 *    a privacy leak across device users if kept;
 *  - the provider's cached backup-folder id (old account's Drive → 404s).
 *
 * What is NOT touched: device-level preferences (folder name, library scope,
 * sort), the wall's local rows re-derive from MediaStore on the next scan, and
 * the old account's cloud files stay untouched in its Drive.
 *
 * Detection anchor: [AppSettingsStore.accountEmail], compared on every
 * successful identity probe (sign-in AND the periodic settings refresh, so a
 * probe that failed during sign-in is caught on the next successful one). The
 * anchor SURVIVES sign-out, so signing back in as somebody else still wipes.
 */
class AccountSwitchGuard(
    private val db: GalleryDatabase,
    private val settings: AppSettingsStore,
    private val provider: ICloudStorageProvider,
    /** cacheDir/originals — the downloaded-originals cache of cloud-only files. */
    private val originalsDir: File,
) {

    /**
     * Observe the freshly probed account email. Returns true when the account
     * CHANGED and all account-scoped state was wiped (the caller should also
     * drop app-layer caches: Coil memory/disk, the My Drive readonly grant).
     */
    suspend fun onAccountObserved(email: String): Boolean {
        val previous = settings.accountEmail.first()
        if (previous == email) return false
        if (previous == null) {
            // First observation ever: either a genuine first sign-in, or an
            // upgrade from before isolation existed — in both cases the staged
            // data can only belong to THIS account, so just anchor it.
            settings.setAccountEmail(email)
            return false
        }

        Log.w(TAG, "account switch: $previous → $email — wiping account-scoped state")
        wipe()
        settings.setAccountEmail(email)
        return true
    }

    /**
     * A NEW session whose identity could not be confirmed (tokens were just
     * persisted but the about() probe failed). On a shared device, staged data
     * anchored to a PREVIOUS account is untrusted from this moment: wipe it
     * before any cloud traffic runs under the new grant. The anchor email is
     * kept, so a later successful probe as a different account re-wipes (a
     * no-op) and as the same account does nothing.
     *
     * Cost of a false positive (same account, flaky probe): one full re-derive
     * cycle — the DB is a pure cache by design. Cost of a false negative was
     * the review's residual high: account A's data visible to account B.
     *
     * Call ONLY right after a new sign-in. Never on routine probe failures —
     * a flaky network must not keep nuking the same account's cache.
     */
    suspend fun onUntrustedSession(): Boolean {
        if (settings.accountEmail.first() == null) return false // no previous account — nothing foreign
        Log.w(TAG, "new session with UNCONFIRMED identity — wiping staged data as untrusted")
        wipe()
        return true
    }

    /**
     * Sign-out: the session's cloud PROJECTION leaves the wall (cloud-only rows
     * deleted, local rows stripped of cloud linkage) — with no account there is
     * no "remote", and a "backed up" badge would be a lie. Kept on purpose:
     * the user's manual queue (local intent, not account data), sync cursors
     * and upload sessions (still valid if the SAME account signs back in; a
     * different account triggers the full wipe above), and the anchor email.
     */
    suspend fun onSignedOut() {
        db.withTransaction {
            db.photoDao().deleteCloudOnlyRows()
            db.photoDao().stripCloudLinkage()
        }
    }

    private suspend fun wipe() {
        db.clearAllTables() // photos + sync_keys + upload_sessions in one transaction
        originalsDir.deleteRecursively()
        provider.clearAccountCaches()
    }
}
