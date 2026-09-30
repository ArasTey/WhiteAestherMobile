package com.whitedns.whiteaesther.data

/**
 * Replaces network addresses in a diagnostics report with placeholders.
 *
 * Separate from the diagnostics screen so it can be tested: the rules are
 * regexes, and a regex that quietly stops matching is the kind of defect that
 * survives review and ships, because nothing fails when the replacement simply
 * does not happen.
 *
 * The user sees the exact text before anything is sent, so the requirement is
 * only that the toggle behaves the way it says it does -- not that this is a
 * complete anonymiser. A report is a report *about* a connection, and the
 * surrounding log has other identifying content handled elsewhere.
 */
internal object DiagnosticsRedaction {

    private val IPV4 = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b(:\d+)?""")

    /** Bracketed, the way an HTTP host header writes it: "[2606:4700::1]:443". */
    private val IPV6_BRACKETED = Regex("""\[[0-9a-fA-F:]+](:\d+)?""")

    /**
     * Bare, the way the resolver list and the engine log write it.
     *
     * This was missing, which made the toggle inconsistent rather than absent.
     * [com.whitedns.whiteaesther.data.DnsServers] accepts a bare literal and the
     * VPN service logs one verbatim when the platform refuses the resolver, so
     * that address reached the report in plain text with "Hide IP addresses"
     * switched on.
     *
     * Two shapes, told apart by structure rather than by counting colons: a
     * compressed address always contains "::", and a full one always has eight
     * groups. A wall-clock time has two colons and no "::", so it matches
     * neither -- which matters here more than anywhere else, because every
     * [com.whitedns.whiteaesther.service.EngineLog] line in the report opens
     * with one.
     */
    private val IPV6_BARE = Regex(
        """(?<![\w:.])(?:[0-9a-fA-F]{1,4}:){0,6}[0-9a-fA-F]{0,4}::""" +
            """(?:[0-9a-fA-F]{1,4}:){0,6}[0-9a-fA-F]{0,4}(?::\d+)?(?!\w)""" +
            """|(?<![\w:.])(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}(?::\d+)?(?!\w)""",
    )

    /** Replaces every address in [line]; returns [line] unchanged when there is none. */
    fun redact(line: String): String = IPV6_BARE.replace(
        IPV6_BRACKETED.replace(IPV4.replace(line, "0.0.0.0:port"), "[ipv6]:port"),
        "[ipv6]",
    )
}
