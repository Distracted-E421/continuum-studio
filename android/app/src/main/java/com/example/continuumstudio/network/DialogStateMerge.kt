package com.example.continuumstudio.network

import com.example.continuumstudio.data.DialogDetails
import com.example.continuumstudio.data.DialogUiState

/**
 * Pure state transitions for the dialog screen.
 *
 * Extracted from the WebSocket client so the rule that matters most can be
 * tested without Android: **draft input must not outlive the dialog it was typed
 * for**. `fetchCurrentDialog` replaces `activeDialog` on every refresh, and it
 * used to leave `comment`, `selectedValue` and `selectedOptions` in place. On a
 * queue of dialogs that meant text typed for one dialog survived into the next
 * and could be submitted against it.
 */
object DialogStateMerge {

    /**
     * Apply a freshly fetched dialog to the current state.
     *
     * Draft fields are cleared only when the dialog identity changes; a refresh
     * of the *same* dialog must not wipe what the user is in the middle of
     * typing.
     */
    fun onFetched(current: DialogUiState, dialog: DialogDetails): DialogUiState {
        val changed = current.activeDialog?.id != dialog.id
        if (!changed) return current.copy(activeDialog = dialog)
        return current.copy(
            activeDialog = dialog,
            selectedValue = "",
            selectedOptions = emptySet(),
            comment = "",
            sliderValue = dialog.dialogType.min ?: 0f,
        )
    }

    /**
     * Clear the answer in progress after it has been sent.
     *
     * Kept next to [onFetched] so the two ways a dialog can end always reset the
     * same set of fields.
     */
    fun onAnswered(current: DialogUiState): DialogUiState = current.copy(
        activeDialog = null,
        selectedValue = "",
        selectedOptions = emptySet(),
        comment = "",
    )
}
