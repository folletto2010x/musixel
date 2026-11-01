package com.unshoo.pixelmusic.presentation.components.wordsync

import com.unshoo.pixelmusic.data.model.SyncedWord

/**
 * How long the last word of a line is assumed to last when nothing states its
 * end. Without a cap it would be stretched to the next line's start, and the
 * sweep would crawl across the whole instrumental gap.
 */
private const val LAST_WORD_FALLBACK_MS = 1_200L

/**
 * Builds a [LyricLine] (the model the word-synced renderer draws) from Pixel
 * Music's [SyncedWord]s.
 *
 * [words] must already be sanitized (see `sanitizeSyncedWords` in LyricsSheet).
 *
 * - A word's end is its own [SyncedWord.endTime] when the provider gave one,
 *   otherwise the start of the next piece, otherwise [LAST_WORD_FALLBACK_MS]
 *   after its start (never past [lineEndMs]).
 * - Pieces with `startsNewWord == false` are syllables of the word before
 *   them: they are glued into one [LyricWord] that keeps their own timing.
 *
 * Returns null when there is nothing to draw.
 */
internal fun buildWordSyncLine(
    lineTimeMs: Long,
    words: List<SyncedWord>,
    lineEndMs: Long,
): LyricLine? {
    if (words.isEmpty()) return null

    val ends = LongArray(words.size) { index ->
        val start = words[index].time.toLong()
        val stated = words[index].endTime?.toLong()
        val next = words.getOrNull(index + 1)?.time?.toLong()
        val end = stated
            ?: next
            ?: minOf(lineEndMs.coerceAtLeast(start), start + LAST_WORD_FALLBACK_MS)
        maxOf(start, end)
    }

    val built = ArrayList<LyricWord>()
    var from = 0
    while (from < words.size) {
        var to = from + 1
        while (to < words.size && !words[to].startsNewWord) to++

        val text = StringBuilder()
        val parts = ArrayList<LyricSyllable>()
        var end = ends[from]
        for (index in from until to) {
            val startChar = text.length
            text.append(words[index].word)
            parts += LyricSyllable(
                startMs = words[index].time.toLong(),
                endMs = ends[index],
                startChar = startChar,
                endChar = text.length,
            )
            end = maxOf(end, ends[index])
        }
        if (text.isNotEmpty()) {
            val joined = text.toString()
            built += LyricWord(
                startMs = words[from].time.toLong(),
                endMs = end,
                text = joined,
                syllables = LyricSyllable.normalized(parts, joined.length),
            )
        }
        from = to
    }
    if (built.isEmpty()) return null

    return LyricLine(
        timeMs = minOf(lineTimeMs, built.first().startMs),
        text = built.joinToString(" ") { it.text },
        words = built,
    )
}
