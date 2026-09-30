package com.whitedns.whiteaesther.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order the endpoint search tries transports in.
 *
 * Kept here rather than inline in the view model because it is the rule the
 * screen and the automatic connect both have to agree on: a search that looks
 * for something a connect would never try reports an empty network on a
 * network that is not empty.
 */
class EndpointSearchLadderTest {

    /**
     * The ladder, built the way the view model builds it.
     *
     * [firstFraming] stands in for the planner's answer so the test says what
     * the order is on each kind of network without a network.
     */
    private fun ladder(
        transport: TunnelProtocol,
        firstFraming: TunnelProtocol,
        sweepOf: List<TunnelProtocol>,
    ): List<TunnelProtocol> =
        if (!transport.isAutomatic) {
            listOf(transport)
        } else {
            (listOf(TunnelProtocol.WIREGUARD, firstFraming) + sweepOf).distinct()
        }

    @Test
    fun wireGuardIsSearchedFirstWhenTheTransportIsAutomatic() {
        for (framing in listOf(TunnelProtocol.H2, TunnelProtocol.H3)) {
            assertEquals(
                TunnelProtocol.WIREGUARD,
                ladder(TunnelProtocol.AUTO, framing, listOf(TunnelProtocol.H3, TunnelProtocol.H2)).first(),
            )
        }
    }

    /**
     * One framing, once.
     *
     * The sweep is written in both directions so it can follow whichever
     * framing led, and putting the two together without de-duplicating handed
     * back the framing that had just been searched -- a wasted full search on
     * the networks where a scan costs the most.
     */
    @Test
    fun noFramingIsSearchedTwice() {
        for (framing in listOf(TunnelProtocol.H2, TunnelProtocol.H3)) {
            val order = ladder(TunnelProtocol.AUTO, framing, listOf(TunnelProtocol.H3, TunnelProtocol.H2))
            assertEquals("framing=$framing", order.size, order.distinct().size)
            assertEquals(3, order.size)
        }
    }

    @Test
    fun bothFramingsAreSearchedEvenOnTheDefault() {
        val order = ladder(TunnelProtocol.AUTO, TunnelProtocol.H3, listOf(TunnelProtocol.H3, TunnelProtocol.H2))
        assertTrue(TunnelProtocol.H3 in order)
        assertTrue(TunnelProtocol.H2 in order)
    }

    /** A transport the user fixed is searched on its own, as they set it. */
    @Test
    fun aFixedTransportIsSearchedAlone() {
        assertEquals(
            listOf(TunnelProtocol.WIREGUARD),
            ladder(TunnelProtocol.WIREGUARD, TunnelProtocol.H3, emptyList()),
        )
        assertEquals(
            listOf(TunnelProtocol.WARP_IN_WARP),
            ladder(TunnelProtocol.WARP_IN_WARP, TunnelProtocol.H3, emptyList()),
        )
    }
}
