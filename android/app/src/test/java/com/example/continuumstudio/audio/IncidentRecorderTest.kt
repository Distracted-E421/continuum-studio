package com.example.continuumstudio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The recorder's retention bound.
 *
 * This runs on every dialog read, so it has to be bounded and it has to be safe. The
 * file naming is load-bearing: names start with a sortable timestamp because pruning
 * assumes lexicographic order is chronological.
 */
class IncidentRecorderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun touch(dir: File, name: String) {
        dir.mkdirs()
        File(dir, name).writeText("x")
    }

    @Test
    fun `pruning keeps the newest files`() {
        val dir = tmp.newFolder("incidents")
        // Timestamp-prefixed names, as the recorder writes them.
        listOf(
            "incident-20260101-100000-manual.txt",
            "incident-20260102-100000-dialog_spoken.txt",
            "incident-20260103-100000-manual.txt",
            "incident-20260104-100000-focus_hold.txt",
        ).forEach { touch(dir, it) }

        IncidentRecorder.prune(dir, max = 2)

        val left = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(2, left.size)
        // The two newest survive; oldest-first eviction.
        assertTrue("newest should survive: $left", left.any { it.contains("20260104") })
        assertTrue("second newest should survive: $left", left.any { it.contains("20260103") })
        assertTrue("oldest should be gone: $left", left.none { it.contains("20260101") })
    }

    @Test
    fun `pruning does nothing below the limit`() {
        val dir = tmp.newFolder("incidents")
        touch(dir, "incident-20260101-100000-manual.txt")
        IncidentRecorder.prune(dir, max = 40)
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test
    fun `pruning an empty or missing directory is safe`() {
        // Called on every record, so it must not throw when there is nothing there.
        IncidentRecorder.prune(File(tmp.root, "nope"), max = 5)
        val empty = tmp.newFolder("empty")
        IncidentRecorder.prune(empty, max = 5)
        assertEquals(0, empty.listFiles()!!.size)
    }

    @Test
    fun `non-txt files are ignored so other data is never deleted`() {
        // The directory is ours, but a future export format should not be silently
        // pruned by a rule that assumes every file is an incident.
        val dir = tmp.newFolder("incidents")
        touch(dir, "incident-20260101-100000-manual.txt")
        touch(dir, "incident-20260102-100000-manual.txt")
        touch(dir, "notes.json")
        IncidentRecorder.prune(dir, max = 1)
        assertTrue(
            "json must survive: ${dir.listFiles()!!.map { it.name }}",
            dir.listFiles()!!.any { it.name == "notes.json" },
        )
    }

    @Test
    fun `the retention limit is large enough to cover more than one drive`() {
        // The realistic gap between "it happened" and "I can look" is a day or two.
        assertTrue("MAX_FILES too small: ${IncidentRecorder.MAX_FILES}", IncidentRecorder.MAX_FILES >= 20)
    }

    @Test
    fun `every trigger has a human label`() {
        // The label is what appears in the report header, so an empty one would make
        // incidents indistinguishable from each other.
        IncidentRecorder.Trigger.entries.forEach { t ->
            assertNotNull(t.label)
            assertTrue("${t.name} needs a label", t.label.length > 3)
        }
    }
}
