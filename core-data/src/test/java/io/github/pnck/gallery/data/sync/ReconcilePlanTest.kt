package io.github.pnck.gallery.data.sync

import io.github.pnck.gallery.data.db.PhotoEntity
import io.github.pnck.gallery.domain.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure classification tests for [planReconcile] — the reliability core. No Android /
 * Room runtime: PhotoEntity is a plain data class.
 */
class ReconcilePlanTest {

    private fun row(
        id: String,
        localUri: String? = null,
        cloudId: String? = null,
        md5: String? = null,
        state: SyncState,
        excluded: Boolean = false,
    ) = PhotoEntity(
        id = id,
        localUri = localUri,
        cloudId = cloudId,
        provider = if (cloudId != null) "G_DRIVE" else null,
        contentHashType = if (md5 != null) "MD5" else null,
        contentHashValue = md5,
        cloudThumbnailUrl = null,
        dateTaken = 1000,
        dateModifiedSec = 1,
        width = 4,
        height = 3,
        sizeBytes = 100,
        bucketId = "b",
        bucketName = "Camera",
        syncState = state,
        excluded = excluded,
    )

    private fun local(uri: String, md5: String?) =
        LocalTruth(uri, md5, dateTaken = 1000, dateModifiedSec = 1, width = 4, height = 3, sizeBytes = 100, bucketId = "b", bucketName = "Camera")

    private fun cloud(cloudId: String, md5: String?) =
        CloudTruth(cloudId, md5, dateTaken = 1000, width = 4, height = 3, thumbnailUrl = null)

    @Test
    fun `phantom cloud-only row is pruned and both files resolve to synced`() {
        // The reported bug: cloud has exactly 2 files (A,B), both present locally, but a
        // stale CLOUD_ONLY phantom lingers. Rebuild → 2 SYNCED, 0 CLOUD_ONLY, phantom gone.
        val local = listOf(local("uri://a", "AAA"), local("uri://b", "BBB"))
        val cloud = listOf(cloud("cA", "AAA"), cloud("cB", "BBB"))
        val existing = listOf(
            row("r1", localUri = "uri://a", cloudId = "cA", md5 = "AAA", state = SyncState.SYNCED),
            row("r2", localUri = "uri://b", cloudId = "cB", md5 = "BBB", state = SyncState.SYNCED),
            row("phantom", cloudId = "cGhost", md5 = "AAA", state = SyncState.CLOUD_ONLY),
        )

        val plan = planReconcile(local, cloud, existing, "G_DRIVE")

        assertEquals(2, plan.upserts.count { it.syncState == SyncState.SYNCED })
        assertEquals(0, plan.upserts.count { it.syncState == SyncState.CLOUD_ONLY })
        assertTrue("phantom must be pruned", "phantom" in plan.deleteIds)
        // Existing rows are reused (ids preserved), not duplicated.
        assertEquals(setOf("r1", "r2"), plan.upserts.map { it.id }.toSet())
    }

    @Test
    fun `device-only file defaults to pending upload (backup-first)`() {
        val plan = planReconcile(listOf(local("uri://x", "XXX")), emptyList(), emptyList(), "G_DRIVE")
        assertEquals(1, plan.upserts.size)
        assertEquals(SyncState.PENDING_UPLOAD, plan.upserts.single().syncState)
        assertEquals("uri://x", plan.upserts.single().localUri)
    }

    @Test
    fun `cloud-only file becomes CLOUD_ONLY`() {
        val plan = planReconcile(emptyList(), listOf(cloud("cZ", "ZZZ")), emptyList(), "G_DRIVE")
        assertEquals(1, plan.upserts.size)
        assertEquals(SyncState.CLOUD_ONLY, plan.upserts.single().syncState)
        assertEquals("cZ", plan.upserts.single().cloudId)
    }

    @Test
    fun `local file with unknown hash stays pending, never assumed synced`() {
        // Even though cloud has a file, a null local md5 can't be proven identical.
        val plan = planReconcile(listOf(local("uri://q", null)), listOf(cloud("cQ", "QQQ")), emptyList(), "G_DRIVE")
        val localRow = plan.upserts.first { it.localUri == "uri://q" }
        assertEquals(SyncState.PENDING_UPLOAD, localRow.syncState)
    }

    @Test
    fun `pending-delete tombstone is preserved, never pruned or resurrected`() {
        val existing = listOf(row("t1", localUri = "uri://d", cloudId = "cD", md5 = "DDD", state = SyncState.PENDING_DELETE))
        // The file still shows up in the local scan, but the user asked to delete it.
        val plan = planReconcile(listOf(local("uri://d", "DDD")), listOf(cloud("cD", "DDD")), existing, "G_DRIVE")
        assertTrue("tombstone not deleted", "t1" !in plan.deleteIds)
        assertTrue("tombstone not resurrected as a new row", plan.upserts.none { it.id == "t1" })
    }
}
