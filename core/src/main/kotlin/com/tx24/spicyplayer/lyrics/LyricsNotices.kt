package com.tx24.spicyplayer.lyrics

import com.tx24.spicyplayer.network.data.LyricsBlends
import com.tx24.spicyplayer.network.data.ProviderAttempt
import com.tx24.spicyplayer.network.data.ProviderAttemptOutcome
import com.tx24.spicyplayer.network.data.ProviderFailureCategory

/**
 * Why there are no lyrics on screen. Each kind has its headline, a one-paragraph explanation,
 * whether trying again could help ([retry]) and whether a report to us could ([report]). [slug]
 * names it in the copied details.
 */
enum class NoticeKind(val slug: String, val title: String, val body: String, val retry: Boolean, val report: Boolean) {
    NoLyrics(
        "no-lyrics", "No lyrics for this song yet",
        "We couldn't find lyrics for this track in any of your sources. New lyrics are added all the time, so check " +
            "back later.",
        retry = false, report = false,
    ),
    NoSources(
        "no-sources", "No lyrics sources are on",
        "Turn at least one source on in Settings → Sources to look up lyrics.",
        retry = false, report = false,
    ),
    Offline(
        "offline", "You're offline",
        "Lyrics need an internet connection the first time you play a song. Songs you've played recently still have " +
            "theirs saved. Reconnect and try again.",
        retry = true, report = false,
    ),
    Unavailable(
        "unavailable", "Lyrics are temporarily unavailable",
        "Our lyrics sources aren't responding right now. Nothing is wrong on your end. Give it a minute and try again.",
        retry = true, report = true,
    ),
    RateLimited(
        "rate-limited", "Slow down a little",
        "Lots of songs were requested in a short time, so we're holding off for a moment. Wait a few seconds and try again.",
        retry = true, report = true,
    ),
    ServerError(
        "server-error", "The lyrics server ran into a problem",
        "A lyrics source returned an error for this song. Try again in a moment. If it keeps happening, copy the " +
            "details and send them to us on Discord.",
        retry = true, report = true,
    ),
    UnknownTrack(
        "unknown-track", "We couldn't read this track",
        "Your player didn't tell us what's playing, so we don't know which lyrics to look up. Skipping to the next " +
            "song and back usually fixes this.",
        retry = false, report = true,
    ),
    RenderFailed(
        "render-failed", "These lyrics couldn't be displayed",
        "The lyrics arrived but couldn't be drawn. Try again, or pick another source for this song.",
        retry = true, report = true,
    ),
    Unknown(
        "unknown", "Something went wrong",
        "An unexpected error stopped the lyrics from loading. Try again, and if it keeps happening, copy the details " +
            "and send them to us on Discord.",
        retry = true, report = true,
    ),
}

/**
 * The notice shown instead of lyrics, picked from what each source actually said. One source
 * failing while the others simply had nothing is a "no lyrics" answer that names the failure,
 * not "sources are unavailable".
 */
object LyricsNotices {
    /** The player didn't give enough to look anything up. */
    val missingMetadata = LyricsState.Error(NoticeKind.UnknownTrack, "The player didn't report a title and artist")

    fun lookupFailed(error: Throwable) = LyricsState.Error(
        NoticeKind.Unknown,
        error.message?.takeIf(String::isNotBlank) ?: error::class.simpleName,
        error::class.simpleName,
    )

    fun renderFailed(source: String, error: Throwable) = LyricsState.Error(
        NoticeKind.RenderFailed,
        "$source: ${error.message ?: error::class.simpleName}",
        error::class.simpleName,
    )

    /** For a finished lookup that picked nothing. [nameOf] turns a source id into its display name. */
    fun noLyrics(attempts: List<ProviderAttempt>, offline: Boolean = false, nameOf: (String) -> String): LyricsState.Error {
        // A blend is built from other sources' answers, not asked itself, so it isn't counted.
        val asked = attempts.filter {
            it.outcome != ProviderAttemptOutcome.DISABLED && it.outcome != ProviderAttemptOutcome.SKIPPED &&
                LyricsBlends.byId(it.sourceId) == null
        }
        if (asked.isEmpty()) return LyricsState.Error(NoticeKind.NoSources)

        val failed = asked.filter { it.outcome in FAILURES }
        val answered = asked.size - failed.size
        val failures = failed.joinToString { "${nameOf(it.sourceId)} (${reason(it)})" }
        val sources = plural(asked.size, "source")
        val serverCodes = failed.mapNotNull(::serverCode)

        return when {
            failed.isEmpty() -> LyricsState.Error(NoticeKind.NoLyrics, "Checked $sources, none had it")
            // The phone itself is offline: the sources' own reasons (a backoff after the first
            // failures, say) would only hide that.
            offline -> LyricsState.Error(NoticeKind.Offline, "Couldn't reach $failures")
            answered > 0 -> LyricsState.Error(NoticeKind.NoLyrics, "${plural(answered, "source")} had none. Couldn't check $failures")
            // A source backing off after its own failures (cooling down, queued) carries no reason
            // of its own, so the ones that tried decide.
            failed.filter { it.failureCategory != null }.let { tried ->
                tried.isNotEmpty() && tried.all { it.failureCategory == ProviderFailureCategory.NETWORK || it.failureCategory == ProviderFailureCategory.TIMEOUT }
            } -> LyricsState.Error(NoticeKind.Offline, "Couldn't reach $failures")
            failed.all(::rateLimited) -> LyricsState.Error(NoticeKind.RateLimited, failures, "429")
            // Server errors at least as often as anything else: the sources are up but failing.
            serverCodes.size * 2 >= failed.size -> LyricsState.Error(
                NoticeKind.ServerError,
                failures,
                serverCodes.groupingBy { it }.eachCount().maxBy { it.value }.key,
            )
            else -> LyricsState.Error(NoticeKind.Unavailable, failures)
        }
    }

    /** A short reason with the HTTP code when there was one, e.g. "HTTP 503" or "timed out". */
    fun reason(attempt: ProviderAttempt): String {
        val message = attempt.message.orEmpty()
        HTTP_CODE.find(message)?.let { return "HTTP ${it.groupValues[1]}" }
        // Most providers file every IOException as NETWORK; a timeout says so in its message.
        if (TIMEOUT.containsMatchIn(message)) return "timed out"
        return when (attempt.outcome) {
            ProviderAttemptOutcome.COOLING_DOWN -> "rate limited"
            ProviderAttemptOutcome.QUEUED -> "queued"
            ProviderAttemptOutcome.MALFORMED_HIT -> "unreadable lyrics"
            ProviderAttemptOutcome.PENDING -> "no answer"
            else -> when (attempt.failureCategory) {
                ProviderFailureCategory.NETWORK -> "no connection"
                ProviderFailureCategory.TIMEOUT -> "timed out"
                ProviderFailureCategory.AUTHENTICATION -> "key rejected"
                ProviderFailureCategory.CLIENT_REQUEST -> "bad request"
                ProviderFailureCategory.SERVER -> "server error"
                ProviderFailureCategory.MALFORMED_RESPONSE -> "unreadable response"
                ProviderFailureCategory.UNKNOWN, null -> "error"
            }
        }
    }

    private fun httpCode(attempt: ProviderAttempt) = HTTP_CODE.find(attempt.message.orEmpty())?.groupValues?.get(1)

    private fun rateLimited(attempt: ProviderAttempt) =
        attempt.outcome == ProviderAttemptOutcome.COOLING_DOWN || httpCode(attempt) == "429"

    /** The 5xx status a source answered with ("5xx" when it only said it was a server error), or null. */
    private fun serverCode(attempt: ProviderAttempt): String? {
        val code = httpCode(attempt)
        return when {
            code != null -> code.takeIf { it.startsWith("5") }
            attempt.failureCategory == ProviderFailureCategory.SERVER -> "5xx"
            else -> null
        }
    }

    private fun plural(count: Int, noun: String) = if (count == 1) "1 $noun" else "$count ${noun}s"

    private val FAILURES = setOf(
        ProviderAttemptOutcome.UNAVAILABLE,
        ProviderAttemptOutcome.MALFORMED_HIT,
        ProviderAttemptOutcome.COOLING_DOWN,
        ProviderAttemptOutcome.QUEUED,
        ProviderAttemptOutcome.PENDING,
    )
    private val HTTP_CODE = Regex("""HTTP (\d{3})""")
    private val TIMEOUT = Regex("timeout|timed out", RegexOption.IGNORE_CASE)
}
