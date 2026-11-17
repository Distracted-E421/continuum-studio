package com.example.continuumstudio.network

import com.example.continuumstudio.data.ChoiceOption
import com.example.continuumstudio.data.DialogDetails
import com.example.continuumstudio.data.DialogTypeInfo
import com.example.continuumstudio.data.DialogUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for draft-input handling across dialog transitions.
 *
 * The rule being protected: text typed for one dialog must never be submittable
 * against another. This was a real bug - `fetchCurrentDialog` replaced the
 * dialog but left the comment and selection behind, so on a queue the previous
 * answer stayed in the box.
 */
class DialogStateMergeTest {

    private fun dialog(
        id: String,
        type: String = "choice",
        options: List<ChoiceOption>? = listOf(
            ChoiceOption("a", "Alpha"),
            ChoiceOption("b", "Bravo"),
        ),
        min: Float? = null,
    ) = DialogDetails(
        id = id,
        title = "Title $id",
        prompt = "Prompt $id",
        dialogType = DialogTypeInfo(type = type, options = options, min = min),
    )

    private fun stateWithDraft(dialogId: String) = DialogUiState(
        activeDialog = dialog(dialogId),
        selectedValue = "a",
        selectedOptions = setOf("a"),
        comment = "typed for $dialogId",
        sliderValue = 42f,
    )

    @Test
    fun `switching dialogs clears the draft`() {
        val merged = DialogStateMerge.onFetched(stateWithDraft("one"), dialog("two"))
        assertEquals("two", merged.activeDialog?.id)
        assertEquals("", merged.comment)
        assertEquals("", merged.selectedValue)
        assertTrue(merged.selectedOptions.isEmpty())
    }

    @Test
    fun `refreshing the same dialog keeps the draft`() {
        val current = stateWithDraft("one")
        val merged = DialogStateMerge.onFetched(current, dialog("one"))
        // A pull-to-refresh must not destroy what the user is typing.
        assertEquals("typed for one", merged.comment)
        assertEquals("a", merged.selectedValue)
        assertEquals(setOf("a"), merged.selectedOptions)
    }

    @Test
    fun `slider resets to the new dialog's minimum`() {
        val current = stateWithDraft("one")
        val next = dialog("two", type = "slider", options = emptyList(), min = 10f)
        val merged = DialogStateMerge.onFetched(current, next)
        assertEquals(10f, merged.sliderValue, 0.001f)
    }

    @Test
    fun `slider defaults to zero when the dialog declares no minimum`() {
        val merged = DialogStateMerge.onFetched(
            stateWithDraft("one"),
            dialog("two", type = "slider", options = emptyList(), min = null),
        )
        assertEquals(0f, merged.sliderValue, 0.001f)
    }

    @Test
    fun `answering clears everything that could be resubmitted`() {
        val answered = DialogStateMerge.onAnswered(stateWithDraft("one"))
        assertEquals(null, answered.activeDialog)
        assertEquals("", answered.comment)
        assertEquals("", answered.selectedValue)
        assertTrue(answered.selectedOptions.isEmpty())
    }

    @Test
    fun `first dialog arriving into empty state keeps no stale fields`() {
        val merged = DialogStateMerge.onFetched(DialogUiState(), dialog("first"))
        assertEquals("first", merged.activeDialog?.id)
        assertEquals("", merged.comment)
    }

    @Test
    fun `unchanged dialog object is still replaced, not dropped`() {
        // The server may update a prompt in place; the refresh must take effect.
        val current = stateWithDraft("one")
        val updated = dialog("one")
        val merged = DialogStateMerge.onFetched(current, updated)
        assertEquals("Prompt one", merged.activeDialog?.prompt)
    }
}
