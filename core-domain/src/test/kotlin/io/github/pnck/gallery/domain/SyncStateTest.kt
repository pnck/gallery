package io.github.pnck.gallery.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncStateTest {

    @Test
    fun `codes round-trip through fromCode`() {
        SyncState.entries.forEach { state ->
            assertEquals(state, SyncState.fromCode(state.code))
        }
    }

    @Test
    fun `codes are stable persistence contract`() {
        // These codes are persisted in Room (PRD §3.7); changing them is a schema migration.
        assertEquals(0, SyncState.PENDING_UPLOAD.code)
        assertEquals(1, SyncState.SYNCED.code)
        assertEquals(2, SyncState.CLOUD_ONLY.code)
        assertEquals(3, SyncState.PENDING_DELETE.code)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown code throws`() {
        SyncState.fromCode(42)
    }
}
