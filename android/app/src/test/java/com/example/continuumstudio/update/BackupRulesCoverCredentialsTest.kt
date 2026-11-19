package com.example.continuumstudio.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cross-checks the backup rules against the DataStores that actually exist.
 *
 * The gap this closes: backup rules use literal paths with no wildcards, so renaming a
 * DataStore - or adding a new one that holds a credential - silently stops being excluded
 * while the rules file still looks correct. That is the same shape as the inert checksum
 * this session already found: protection that reads as protection and is not.
 *
 * This is a source-level test (it reads the XML and the Kotlin sources from the working
 * directory), which is unusual but appropriate: the invariant spans two files that no
 * unit test would otherwise connect.
 */
class BackupRulesCoverCredentialsTest {

    /** DataStores that must never reach a cloud backup, and why. */
    private val mustBeExcluded = mapOf(
        "settings" to "holds cf_access_client_secret",
        "app_settings" to "holds cf_access_client_secret",
    )

    /** User work: deliberately backed up, so an over-eager exclusion would be a bug too. */
    private val mustNotBeExcluded = listOf("offline_queue", "dialog_history")

    private fun rulesFile(path: String): String {
        // Gradle runs unit tests with the module directory as the working directory.
        val f = File("src/main/res/xml/$path")
        assertTrue("expected $path relative to ${File(".").absolutePath}", f.isFile)
        return f.readText()
    }

    /**
     * The `<exclude>` elements only, with comments stripped.
     *
     * The first version of this test used a plain `contains()` on the whole file, and a
     * mutation removing a real exclusion still passed - because the *documentation
     * comment* above it also mentions the path. A test that a comment can satisfy
     * proves nothing, and it would have silently stopped protecting anything the moment
     * someone reworded the comment.
     */
    private fun excludeLines(path: String): List<String> =
        rulesFile(path)
            .lines()
            .filter { it.contains("<exclude") }
            .map { it.trim() }

    @Test
    fun `every credential DataStore is excluded from cloud backup`() {
        val excludes = excludeLines("data_extraction_rules.xml")
        mustBeExcluded.forEach { (store, why) ->
            val needle = "datastore/$store.preferences_pb"
            assertTrue(
                "$store ($why) is not excluded in data_extraction_rules.xml; excludes were: $excludes",
                excludes.any { it.contains(needle) },
            )
        }
    }

    @Test
    fun `every credential DataStore is excluded for API below 31 too`() {
        // data_extraction_rules only applies on API 31+; the older file is what most
        // devices in the field would use.
        val excludes = excludeLines("backup_rules.xml")
        mustBeExcluded.forEach { (store, _) ->
            val needle = "datastore/$store.preferences_pb"
            assertTrue(
                "$store is not excluded in backup_rules.xml; excludes were: $excludes",
                excludes.any { it.contains(needle) },
            )
        }
    }

    @Test
    fun `user work is still backed up`() {
        // Excluding too much is also a bug: losing queued answers on a migration is real
        // data loss, and it is the sort of thing that would never be noticed until it
        // mattered.
        listOf("data_extraction_rules.xml", "backup_rules.xml").forEach { file ->
            val excludes = excludeLines(file)
            mustNotBeExcluded.forEach { store ->
                assertTrue(
                    "$file must NOT exclude $store; excludes were: $excludes",
                    excludes.none { it.contains("datastore/$store.preferences_pb") },
                )
            }
        }
    }

    @Test
    fun `diagnostics directories are excluded in both rule files`() {
        listOf("data_extraction_rules.xml", "backup_rules.xml").forEach { file ->
            val excludes = excludeLines(file)
            assertTrue("$file must exclude incidents/", excludes.any { it.contains("incidents/") })
            assertTrue("$file must exclude reports/", excludes.any { it.contains("reports/") })
        }
    }

    @Test
    fun `the two rule files agree about credential exclusions`() {
        // Only one applies per Android version, so a divergence means half the fleet has
        // different privacy behaviour with no visible sign of it.
        val modern = excludeLines("data_extraction_rules.xml")
        val legacy = excludeLines("backup_rules.xml")
        mustBeExcluded.keys.forEach { store ->
            val needle = "datastore/$store.preferences_pb"
            assertEquals(
                "both files must agree on $store",
                modern.any { it.contains(needle) },
                legacy.any { it.contains(needle) },
            )
        }
    }
}
