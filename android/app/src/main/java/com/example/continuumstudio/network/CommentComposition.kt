package com.example.continuumstudio.network

/**
 * Compose the comment field sent with an answer.
 *
 * The daemon's answer payload is `{id, selection, comment}` - there is no image
 * field - so an attached image travels as a markdown link appended to the
 * comment. The app used to store the uploaded URL and never read it, which made
 * image attachment a feature that existed in code and nowhere else.
 *
 * Kept as a pure function because the interesting cases are all about not
 * producing stray whitespace or an empty comment.
 */
internal fun composeComment(comment: String?, imageUrl: String?): String? {
    val parts = buildList {
        comment?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
        imageUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { add("[image]($it)") }
    }
    return parts.joinToString("\n").takeIf { it.isNotEmpty() }
}
