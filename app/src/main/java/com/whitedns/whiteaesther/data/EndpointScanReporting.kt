package com.whitedns.whiteaesther.data

/**
 * What the engine's own words mean, from the user's point of view.
 *
 * The endpoint scan can end for three quite different reasons, and the engine
 * does not distinguish them in the text it returns. Left alone the screen shows
 * the engine's raw line — a URL, a route name, a socket error chain — as though
 * it were the result of the search:
 *
 * ```
 * registration is on hold for another 30s: api: registration: direct route ->
 * api: registration: error sending request for url
 * (https://api.cloudflareclient.com/v0a4471/reg); camouflaged route ->
 * api: connect to 141.101.113.18:443 timed out
 * ```
 *
 * That is a request to retry, not a verdict, and it is not something a person
 * can act on. This maps it to the two or three things a person *can* act on.
 *
 * Pure and total on purpose: an unrecognised message becomes [UNKNOWN] rather
 * than being guessed at, so a future engine message can never be misreported as
 * something it is not.
 */
enum class EndpointScanOutcome {
    /**
     * The engine gave up on a retry budget and says it is waiting. The search
     * has not concluded, so this is progress, not a failure.
     */
    STILL_WAITING,

    /**
     * A host the app must reach could not be reached at all — DNS failed, or
     * the connection never completed. Almost always the network, not the app.
     */
    NETWORK_UNREACHABLE,

    /**
     * The search finished and nothing answered. The network worked; there was
     * simply no way out from here.
     */
    NOTHING_ANSWERED,

    /** The engine said something we do not recognise. */
    UNKNOWN,
}

internal object EndpointScanReporting {

    /**
     * Classifies what the engine returned.
     *
     * [message] is the engine's own text and may be null or blank, which is
     * what an empty result set arrives as.
     */
    fun classify(message: String?): EndpointScanOutcome {
        val text = message?.trim().orEmpty()
        if (text.isEmpty()) return EndpointScanOutcome.NOTHING_ANSWERED
        val lower = text.lowercase()

        // The engine reports a live retry budget in the same channel it reports
        // a dead end. It is checked first because a waiting message usually also
        // contains the failure that caused the wait.
        if (WAITING_MARKERS.any { it in lower }) return EndpointScanOutcome.STILL_WAITING

        if (UNREACHABLE_MARKERS.any { it in lower }) {
            return EndpointScanOutcome.NETWORK_UNREACHABLE
        }
        return EndpointScanOutcome.UNKNOWN
    }

    /**
     * True when the outcome should be shown as progress rather than as a
     * failure the user has to react to.
     */
    fun isProgress(outcome: EndpointScanOutcome): Boolean =
        outcome == EndpointScanOutcome.STILL_WAITING

    private val WAITING_MARKERS = listOf(
        "on hold for another",
        "still to run",
        "retrying",
        "retrying over",
        "handing over to the camouflaged route",
    )

    private val UNREACHABLE_MARKERS = listOf(
        "error sending request for url",
        "timed out",
        "connection refused",
        "name resolution",
        "dns error",
        "failed to lookup address",
        "no route to host",
        "network is unreachable",
        "connection reset",
        "unexpected eof",
    )
}
