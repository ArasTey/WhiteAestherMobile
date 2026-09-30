package com.whitedns.whiteaesther.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Hide IP addresses" toggle has to actually hide addresses.
 *
 * A redaction rule that stops matching does not fail anything -- the report
 * still builds, still previews, still sends -- so it would ship unnoticed. It
 * had already shipped once: the rules covered a bracketed IPv6 literal and IPv4,
 * and not the bare form. DnsServers accepts a bare literal and the VPN service
 * logs one verbatim when the platform refuses the resolver, so exactly the
 * addresses most worth hiding were the ones that got through.
 */
class DiagnosticsRedactionTest {

    @Test
    fun a_bare_ipv6_literal_is_redacted() {
        // The line the service actually writes when the platform will not take
        // a resolver. Before the fix this was returned untouched.
        val line = "the interface would not take the resolver 2606:4700::1"

        val out = DiagnosticsRedaction.redact(line)

        assertTrue("bare IPv6 survived redaction: $out", !out.contains("2606:4700"))
        assertEquals("the interface would not take the resolver [ipv6]", out)
    }

    @Test
    fun the_loopback_and_single_group_forms_are_redacted() {
        assertEquals("resolver [ipv6] rejected", DiagnosticsRedaction.redact("resolver ::1 rejected"))
        assertEquals(
            "tunnelled to [ipv6] ok",
            DiagnosticsRedaction.redact("tunnelled to 2001:0db8:85a3:0000:0000:8a2e:0370:7334 ok"),
        )
    }

    @Test
    fun a_port_after_a_bare_address_is_redacted_with_it() {
        val out = DiagnosticsRedaction.redact("peer 2606:4700::1111:443 closed")

        assertTrue("address and port survived: $out", !out.contains("2606:4700"))
        assertEquals("peer [ipv6] closed", out)
    }

    @Test
    fun two_addresses_on_one_line_are_both_redacted() {
        val out = DiagnosticsRedaction.redact("2606:4700::1 and 2001:db8:0:0:0:0:0:2 seen")

        assertTrue("one survived: $out", !out.contains("2606:4700") && !out.contains("2001:db8"))
    }

    @Test
    fun ipv4_and_bracketed_ipv6_still_work() {
        // The rules that were already here. Adding the bare form must not have
        // displaced them.
        assertEquals(
            "peer 0.0.0.0:port closed",
            DiagnosticsRedaction.redact("peer 162.159.197.3:443 closed"),
        )
        assertEquals(
            "host [ipv6]:port refused",
            DiagnosticsRedaction.redact("host [2606:4700::1]:443 refused"),
        )
    }

    /**
     * Every EngineLog line in a report opens with a timestamp, so a rule that
     * simply looked for colons would turn the report into a row of placeholders
     * and destroy the one thing the reader needs to correlate it with.
     */
    @Test
    fun a_clock_time_is_not_mistaken_for_an_address() {
        assertEquals(
            "2026-09-30 16:04:31 INFO engine start",
            DiagnosticsRedaction.redact("2026-09-30 16:04:31 INFO engine start"),
        )
        assertEquals(
            "elapsed 00:01:05 connected",
            DiagnosticsRedaction.redact("elapsed 00:01:05 connected"),
        )
        assertEquals(
            "note: something happened at 12:34:56 today",
            DiagnosticsRedaction.redact("note: something happened at 12:34:56 today"),
        )
    }

    @Test
    fun hostnames_and_ordinary_words_are_left_alone() {
        val untouched = listOf(
            "match DOMAIN-SUFFIX,dns.google using PROXY",
            "match DOMAIN,api.example-cloud.com using PROXY",
            "Time::now elapsed",
            "version 1.2.3 stable-preview",
            "carrier AETHER on MASQUE_H3",
            "retry 3 of 8 in 12s",
        )
        for (line in untouched) {
            assertEquals("mangled: $line", line, DiagnosticsRedaction.redact(line))
        }
    }
}
