package com.whitedns.whiteaesther.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The configuration every engine call is made with.
 *
 * The bridge refuses `transport: "auto"` outright, and `parse()` runs before
 * anything is read, so a call that hands it Automatic gets a refusal rather
 * than an answer. That reads exactly like a "no", which is how `hasIdentity`
 * came to report that a phone held no key when it held one -- the button came
 * back on every launch and pressing it returned the key instantly from disk.
 *
 * The failure is silent and the symptom is a plausible answer, so it is worth
 * pinning: three call sites had each grown their own resolution and one of
 * them forgot.
 */
class EngineConfigForTest {

    private fun defaults() = AppSettings()

    /**
     * The rule itself, as the view model applies it. Mirrors
     * `MainViewModel.engineConfigFor`, which is the one place it is decided.
     */
    private fun resolved(settings: AppSettings): AppSettings = settings.copy(
        endpointMode = EndpointMode.AUTOMATIC,
        customEndpoint = "",
        transport = if (settings.transport.isAutomatic) TunnelProtocol.H2 else settings.transport,
    )

    @Test
    fun automaticBecomesAConcreteFraming() {
        // The defaults, which is the point: a fresh install has Automatic, and
        // Automatic is the name the bridge will not take.
        assertEquals(TunnelProtocol.AUTO, defaults().transport)
        assertTrue(TunnelProtocol.AUTO.isAutomatic)

        assertFalse(
            "the resolved transport must be one the bridge accepts",
            resolved(defaults()).transport.isAutomatic,
        )
    }

    @Test
    fun aTransportTheUserFixedIsLeftAlone() {
        for (fixed in TunnelProtocol.entries.filterNot { it.isAutomatic }) {
            assertEquals(fixed, resolved(defaults().copy(transport = fixed)).transport)
        }
    }

    /**
     * The bridge answers about the slot the transport names, so a pinned
     * endpoint has to go: left in, `peerFallback` would demand a peer the
     * question was never about, and a "do I have a key" call would fail for a
     * reason that has nothing to do with keys.
     */
    @Test
    fun aPinnedEndpointIsDropped() {
        val pinned = defaults().copy(
            endpointMode = EndpointMode.CUSTOM_ONLY,
            customEndpoint = "162.159.197.3:443",
        )
        val result = resolved(pinned)

        assertEquals(EndpointMode.AUTOMATIC, result.endpointMode)
        assertEquals("", result.customEndpoint)
    }

    /** Which WireGuard is named decides which slot is asked about. */
    @Test
    fun wireGuardNamesTheWireguardSlot() {
        assertEquals(
            TunnelProtocol.WIREGUARD,
            resolved(defaults().copy(transport = TunnelProtocol.WIREGUARD)).transport,
        )
    }
}