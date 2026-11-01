package com.unshoo.pixelmusic.data.network.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Syllable-timed lyrics from LyricsPlus, the open backend behind the YouLy+
 * extension (it aggregates Apple Music, QQ Music and Musixmatch).
 *
 * Based on BitChord's LyricsPlus provider
 * (https://github.com/kushagrasinghx/BitChord, GPL-3.0), file
 * shared/.../data/lyrics/LyricsPlus.kt, adapted for Pixel Music.
 *
 * The backend runs on volunteer-hosted mirrors, many of which are down at any
 * given time. As in BitChord, every mirror is asked at once and the first
 * usable answer wins; the winner is remembered for the next track.
 *
 * Title, artist, album and duration of the track are sent to those mirrors.
 *
 * The result is handed back as Kugou-style text (`[start,dur]<offset,dur,0>word`)
 * which `LyricsUtils.parseLyrics` already understands and which, unlike
 * enhanced LRC, keeps the end of every word and syllable.
 */
object LyricsPlusProvider {

    private val MIRRORS = listOf(
        "https://lyricsplus.prjktla.my.id",
        "https://lyricsplus.atomix.one",
        "https://lyricsplus.binimum.org",
        "https://lyricsplus.prjktla.workers.dev",
        "https://lyricsplus-seven.vercel.app",
        "https://lyrics-plus-backend.vercel.app",
    )

    private const val USER_AGENT = "PixelMusic (https://github.com/ianshulyadav/PixelMusic)"
    private const val TIMEOUT_SECONDS = 6L

    /** A track whose last line starts this far past the song's end is another song. */
    private const val DURATION_SLACK_MS = 15_000L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val lastGood = AtomicReference<String?>(null)

    /**
     * Raw word/syllable-synced lyrics for the track, or null when no mirror has
     * them. Never throws for network or parsing problems.
     */
    suspend fun fetchRawLyrics(
        client: OkHttpClient,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): String? = coroutineScope {
        val fast = client.newBuilder()
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS)
            .build()

        val hosts = lastGood.get()
            ?.let { listOf(it) + MIRRORS.filterNot { mirror -> mirror == it } }
            ?: MIRRORS

        val pending = hosts.map { host ->
            host to async(Dispatchers.IO) { fetch(fast, host, title, artist, durationMs, album) }
        }.toMutableList()

        try {
            while (pending.isNotEmpty()) {
                val (host, raw) = select {
                    pending.forEach { (host, job) -> job.onAwait { host to it } }
                }
                pending.removeAll { it.first == host }
                if (!raw.isNullOrBlank()) {
                    lastGood.set(host)
                    return@coroutineScope raw
                }
            }
            null
        } finally {
            pending.forEach { it.second.cancel() }
        }
    }

    private suspend fun fetch(
        client: OkHttpClient,
        host: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
    ): String? = withContext(Dispatchers.IO) {
        val url = runCatching {
            "$host/v2/lyrics/get".toHttpUrl().newBuilder()
                .addQueryParameter("title", title)
                .addQueryParameter("artist", artist)
                .apply {
                    val seconds = durationMs / 1000
                    if (seconds > 0) addQueryParameter("duration", seconds.toString())
                    if (!album.isNullOrBlank()) addQueryParameter("album", album)
                }
                .build()
        }.getOrNull() ?: return@withContext null

        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        val body = client.newCall(request).awaitBody() ?: return@withContext null
        val response = runCatching { json.decodeFromString<LyricsPlusResponse>(body) }.getOrNull()
            ?: return@withContext null

        toKugouLrc(
            lines = response.lyrics.orEmpty().map { line ->
                PlusLine(
                    startMs = line.time,
                    durationMs = line.duration,
                    text = line.text,
                    syllables = line.syllabus.orEmpty().mapNotNull { syllable ->
                        val text = syllable.text ?: return@mapNotNull null
                        val time = syllable.time ?: return@mapNotNull null
                        PlusSyllable(time, syllable.duration ?: 0L, text)
                    },
                )
            },
            songDurationMs = durationMs,
        )
    }

    /** Body of a successful call, or null for any failure. Cancels the call if the coroutine is cancelled. */
    private suspend fun Call.awaitBody(): String? = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val text = runCatching {
                    response.use { if (it.isSuccessful) it.body?.string() else null }
                }.getOrNull()
                if (continuation.isActive) continuation.resume(text)
            }
        })
    }

    // ---------------------------------------------------------------------
    // Conversion (pure, unit-testable)
    // ---------------------------------------------------------------------

    internal data class PlusSyllable(val startMs: Long, val durationMs: Long, val text: String)

    internal data class PlusLine(
        val startMs: Long?,
        val durationMs: Long?,
        val text: String?,
        val syllables: List<PlusSyllable>,
    )

    /**
     * Writes [lines] as Kugou-style text:
     * `[lineStartMs,lineDurationMs]<offsetMs,durationMs,0>text...`
     * where offsets are relative to the line start.
     *
     * A syllable keeps whatever whitespace the API gave it: that whitespace is
     * the only thing saying where a word ends, and the parser reads it back as
     * `startsNewWord`. Scripts written without spaces (Han, kana, Thai) get a
     * boundary after every run, otherwise a whole line would become one word.
     *
     * Returns null when nothing usable is left, or when the lyrics run well
     * past the end of the song (a different track).
     */
    internal fun toKugouLrc(lines: List<PlusLine>, songDurationMs: Long): String? {
        val built = ArrayList<Pair<Long, String>>()
        var lastStart = 0L

        for (line in lines) {
            val start = line.startMs ?: continue
            // Blank runs stay in the text: a lone space is what ends a word.
            val syllables = line.syllables
            val timed = syllables.filter { it.text.isNotBlank() }
            if (timed.isNotEmpty()) {
                val lineStart = minOf(start, timed.minOf { it.startMs }).coerceAtLeast(0L)
                val lineEnd = timed.maxOf { maxOf(it.startMs, it.startMs + it.durationMs) }
                val body = StringBuilder()
                syllables.forEachIndexed { index, syllable ->
                    val offset = (syllable.startMs - lineStart).coerceAtLeast(0L)
                    val duration = syllable.durationMs.coerceAtLeast(0L)
                    var text = sanitize(syllable.text)
                    val next = syllables.getOrNull(index + 1)?.text
                    if (!text.last().isWhitespace() && needsBoundary(text.last(), next)) text += " "
                    body.append('<').append(offset).append(',').append(duration).append(",0>").append(text)
                }
                built += lineStart to "[$lineStart,${(lineEnd - lineStart).coerceAtLeast(0L)}]$body"
                lastStart = maxOf(lastStart, lineStart)
            } else if (!line.text.isNullOrBlank()) {
                // Line-synced only: still worth showing, without word timing.
                val text = sanitize(line.text).trim()
                if (text.isNotEmpty()) {
                    built += start to "[$start,${(line.durationMs ?: 0L).coerceAtLeast(0L)}]$text"
                    lastStart = maxOf(lastStart, start)
                }
            }
        }

        if (built.isEmpty()) return null
        if (songDurationMs > 0 && lastStart > songDurationMs + DURATION_SLACK_MS) return null
        // The format needs at least one timed word to be recognised as such.
        if (built.none { it.second.contains("<") }) return null
        return built.sortedBy { it.first }.joinToString("\n") { it.second }
    }

    /** Tags and line breaks inside a syllable would corrupt the text format. */
    private fun sanitize(raw: String): String =
        raw.replace('\n', ' ').replace('\r', ' ').replace("<", "").replace(">", "")
            .ifBlank { " " }

    private fun needsBoundary(last: Char, next: String?): Boolean {
        if (next == null) return false
        val first = next.firstOrNull() ?: return false
        if (first.isWhitespace()) return false
        return last.isUnspacedScript() || first.isUnspacedScript()
    }

    /** Scripts written without spaces between words. */
    private fun Char.isUnspacedScript(): Boolean =
        this in '\u3400'..'\u9FFF' ||
            this in '\u3040'..'\u30FF' ||
            this in '\u3000'..'\u303F' ||
            this in '\uFF00'..'\uFFEF' ||
            this in '\u0E00'..'\u0E7F'

    @Serializable
    private data class LyricsPlusResponse(
        val lyrics: List<PlusJsonLine>? = null,
    )

    @Serializable
    private data class PlusJsonLine(
        val time: Long? = null,
        val duration: Long? = null,
        val text: String? = null,
        val syllabus: List<PlusJsonSyllable>? = null,
    )

    @Serializable
    private data class PlusJsonSyllable(
        val time: Long? = null,
        val duration: Long? = null,
        val text: String? = null,
    )
}
