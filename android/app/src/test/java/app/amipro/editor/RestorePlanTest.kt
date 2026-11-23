package app.amipro.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestorePlanTest {
    private fun rec(id: String, uuid: String) = StackLink.OwnedRecord(id, uuid, "/old/$id")

    @Test fun confirmedFilesAreRestoredAndGoneOnesSkippedAndItIsDone() {
        val plan = StackSync.planRestore(listOf(rec("L1", "u1"), rec("L2", "u2"))) {
            if (it == "u1") FilenLookup(true, "/new/one") else FilenLookup(false, "")
        }
        assertEquals(listOf("L1"), plan.entries.map { it.linkId })
        assertEquals("/new/one", plan.entries[0].path)
        assertTrue(plan.done)
    }

    @Test fun filenNotAnsweringKeepsRestoreOpenForTheNextStart() {
        val plan = StackSync.planRestore(listOf(rec("L1", "u1"), rec("L2", "u2"))) { if (it == "u1") null else FilenLookup(true, "/p") }
        assertEquals(listOf("L2"), plan.entries.map { it.linkId })
        assertFalse(plan.done)
    }

    @Test fun anEmptyPathFromFilenFallsBackToStacksPath() {
        val plan = StackSync.planRestore(listOf(rec("L1", "u1"))) { FilenLookup(true, "") }
        assertEquals("/old/L1", plan.entries[0].path)
    }

    @Test fun aTrashedFileIsNotThereEvenIfFilenSaysItExists() {
        assertFalse(FilenLookup.of(exists = true, trashed = true, path = "/p").exists)
        assertTrue(FilenLookup.of(exists = true, trashed = false, path = "/p").exists)
        assertFalse(FilenLookup.of(exists = false, trashed = false, path = "").exists)
    }

    @Test fun aTrashedFileIsNotRestoredAsLive() {
        val plan = StackSync.planRestore(listOf(rec("L1", "u1"))) { FilenLookup.of(true, true, "/p") }
        assertTrue(plan.entries.isEmpty())
        assertTrue(plan.done)
    }
}
