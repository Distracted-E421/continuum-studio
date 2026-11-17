package com.example.continuumstudio.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for composing an answer comment with an attached image.
 *
 * The daemon has no image field on answers, so the image URL rides in the comment
 * as a markdown link. The cases that matter are the empty ones: a stray blank
 * comment or a lone newline would be sent as a real comment.
 */
class CommentCompositionTest {

    @Test
    fun `no comment and no image sends nothing`() {
        assertNull(composeComment(null, null))
        assertNull(composeComment("", null))
        assertNull(composeComment("   ", null))
        assertNull(composeComment(null, ""))
    }

    @Test
    fun `comment only is unchanged`() {
        assertEquals("looks good", composeComment("looks good", null))
    }

    @Test
    fun `image only becomes a link`() {
        assertEquals(
            "[image](http://host/api/images/a.png)",
            composeComment(null, "http://host/api/images/a.png"),
        )
    }

    @Test
    fun `both are separated by a single newline in order`() {
        assertEquals(
            "looks good\n[image](http://h/a.png)",
            composeComment("looks good", "http://h/a.png"),
        )
    }

    @Test
    fun `whitespace is trimmed so no blank comment is sent`() {
        assertEquals("note", composeComment("  note  ", null))
        assertEquals("[image](u)", composeComment("   ", "u"))
    }

    @Test
    fun `an empty image url does not produce a broken link`() {
        assertEquals("note", composeComment("note", ""))
        assertEquals("note", composeComment("note", "   "))
    }
}
