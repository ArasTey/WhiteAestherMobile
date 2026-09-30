package com.whitedns.whiteaesther.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's own words, and what a person should be told instead.
 *
 * Every message below is real output captured from the engine during manual
 * testing on an Android 14 emulator, not invented for the test. The point of
 * the first case is the one that shipped broken: a live retry budget rendered
 * to the user, in red, inside the card that is supposed to list endpoints.
 */
class EndpointScanReportingTest {

    /** Captured verbatim from logcat during a real scan. */
    private val liveRetryBudget =
        "registration is on hold for another 30s: api: registration: direct route -> " +
            "api: registration: error sending request for url " +
            "(https://api.cloudflareclient.com/v0a4471/reg); camouflaged route -> " +
            "api: connect to 141.101.113.18:443 timed out"

    @Test
    fun a_live_retry_budget_is_progress_not_a_failure() {
        // This is the message that was shown to the user as though it were the
        // result of the search. It is the engine saying it is still working.
        assertEquals(
            EndpointScanOutcome.STILL_WAITING,
            EndpointScanReporting.classify(liveRetryBudget),
        )
        assertTrue(EndpointScanReporting.isProgress(EndpointScanOutcome.STILL_WAITING))
    }

    @Test
    fun a_wait_is_recognised_even_when_the_cause_is_a_failure() {
        // Ordering matters: this text contains "error sending request for url"
        // and "timed out", so the unreachable check would match first if the
        // waiting check did not come before it. The user is waiting, not
        // finished, and saying "no route out" here would be a lie.
        val stillWaiting = EndpointScanReporting.classify(liveRetryBudget)
        assertFalse(
            "a waiting message must not be reported as a dead network",
            stillWaiting == EndpointScanOutcome.NETWORK_UNREACHABLE,
        )
    }

    @Test
    fun an_unreachable_host_is_reported_as_a_network_problem() {
        assertEquals(
            EndpointScanOutcome.NETWORK_UNREACHABLE,
            EndpointScanReporting.classify(
                "api: registration: error sending request for url " +
                    "(https://api.cloudflareclient.com/v0a4471/reg)",
            ),
        )
        assertEquals(
            EndpointScanOutcome.NETWORK_UNREACHABLE,
            EndpointScanReporting.classify("api: connect to 141.101.113.18:443 timed out"),
        )
        assertEquals(
            EndpointScanOutcome.NETWORK_UNREACHABLE,
            EndpointScanReporting.classify("dns error: no record found for name"),
        )
        assertFalse(
            EndpointScanReporting.isProgress(
                EndpointScanReporting.classify("api: connect to 10.0.0.1:443 timed out"),
            ),
        )
    }

    @Test
    fun an_empty_result_means_nothing_answered_rather_than_an_error() {
        // The scan completed and every candidate failed. That is different from
        // the network being down, and different from an engine error.
        assertEquals(
            EndpointScanOutcome.NOTHING_ANSWERED,
            EndpointScanReporting.classify(null),
        )
        assertEquals(
            EndpointScanOutcome.NOTHING_ANSWERED,
            EndpointScanReporting.classify(""),
        )
        assertEquals(
            EndpointScanOutcome.NOTHING_ANSWERED,
            EndpointScanReporting.classify("   "),
        )
    }

    @Test
    fun an_unrecognised_message_is_never_guessed_at() {
        // Anything we do not understand must stay UNKNOWN, so a future engine
        // message can never be reported to the user as something it is not.
        assertEquals(
            EndpointScanOutcome.UNKNOWN,
            EndpointScanReporting.classify("the moon is in the seventh house"),
        )
    }

    @Test
    fun the_retry_markers_cover_the_forms_the_engine_actually_writes() {
        for (message in listOf(
            "registration is on hold for another 30s",
            "30s of the wait from the last attempt is still to run",
            "registration retrying over a camouflaged route",
        )) {
            assertEquals(
                "expected WAITING for: $message",
                EndpointScanOutcome.STILL_WAITING,
                EndpointScanReporting.classify(message),
            )
            assertTrue(EndpointScanReporting.isProgress(EndpointScanReporting.classify(message)))
        }
    }
}
