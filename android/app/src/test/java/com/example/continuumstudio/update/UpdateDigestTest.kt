package com.example.continuumstudio.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The digest parser guards a self-update path with `REQUEST_INSTALL_PACKAGES`.
 *
 * Every OTA call site used to pass `sha256 = null` and the downloader only verified
 * "if provided", so an APK was downloaded and installed with no integrity check at all.
 * These tests pin the parsing, and the enforcement is pinned by the downloader refusing
 * to proceed without an expected hash.
 */
class UpdateDigestTest {

    private fun parse(raw: String?) = OtaUpdateManager.parseDigest(raw)

    @Test
    fun `github's sha256 prefix is stripped`() {
        // The exact shape GitHub returns for release assets.
        assertEquals(
            "fe4e6aca6b977fbefa1230f7e0e01040bbc5b98d0cf3859df83a1a4a63c353f0",
            parse("sha256:fe4e6aca6b977fbefa1230f7e0e01040bbc5b98d0cf3859df83a1a4a63c353f0"),
        )
    }

    @Test
    fun `a bare hex digest is accepted`() {
        val hex = "a".repeat(64)
        assertEquals(hex, parse(hex))
    }

    @Test
    fun `uppercase hex is normalised`() {
        // Comparison is case-insensitive anyway, but normalising means a log line shows
        // one form regardless of where the hash came from.
        val upper = "ABCDEF".repeat(10) + "ABCD"
        assertEquals(upper.lowercase(), parse(upper))
        assertEquals(upper.lowercase(), parse("sha256:$upper"))
    }

    @Test
    fun `null and blank yield null, meaning no usable checksum`() {
        assertNull(parse(null))
        assertNull(parse(""))
        assertNull(parse("   "))
        assertNull(parse("sha256:"))
    }

    @Test
    fun `a wrong-length value is rejected rather than compared`() {
        // A short or long value is not a SHA-256. Comparing against it would make every
        // download fail, which looks identical to a corrupted asset and would send
        // someone hunting a supply-chain problem that does not exist.
        assertNull(parse("abc"))
        assertNull(parse("a".repeat(63)))
        assertNull(parse("a".repeat(65)))
    }

    @Test
    fun `non-hex characters are rejected`() {
        // 'z' is not hex; a value containing it cannot be a SHA-256.
        assertNull(parse("z".repeat(64)))
        assertNull(parse("sha256:" + "g".repeat(64)))
    }

    @Test
    fun `a different algorithm prefix is not silently treated as sha256`() {
        // `sha512:<hex>` would be 128 characters, so length rejects it. This is worth
        // pinning because accepting it would mean comparing a sha512 against a sha256.
        assertNull(parse("sha512:" + "a".repeat(128)))
    }

    @Test
    fun `whitespace around the value is tolerated`() {
        val hex = "b".repeat(64)
        assertEquals(hex, parse("  sha256:$hex  "))
    }
}
