# 23 — UI & Loading Improvements

## The problem, as reported and as reproduced

"Endpoint Finder is not working", "the app feels slow", "loading and UI responsiveness
need substantial improvement".

Reproduced on the emulator: pressing **Find endpoints** produced a button that said
"Stop" and a results card that said nothing for 20 seconds, then displayed the engine's
raw retry state in red for over two minutes.

## What changed

### 1. A state the user can act on

Before, one `error` field carried everything the engine said. Now
`EndpointScanReporting` separates four outcomes, and each gets its own sentence:

| Outcome | Shown as |
| --- | --- |
| still retrying | message, not error — "Still trying to reach Cloudflare. This is taking longer than usual." |
| network unreachable | message + no raw text — "Could not reach the server that issues this app's key. That is the network, not the app…" |
| nothing answered | message, not error — "The search finished, but nothing answered from here. The network works — there is just no way out on it right now." |
| unrecognised | the engine's text is **kept**, because it is the only clue |

The raw text is never invented or reworded — it is either classified into one of the
above or shown as-is.

### 2. A button that acknowledges immediately and cannot be double-pressed

**"Get my key, then find endpoints"**, with a line under it saying exactly what it will
do:

> On a network that blocks the normal route, this opens a carrier briefly, asks for this
> phone's key through it, closes the carrier, and then searches. The VPN runs for both
> steps, and nothing is connected afterwards.

That last clause is deliberate: the user asked for the VPN to be on for both steps, and
the honest thing is to say what "on" means here — a carrier, up briefly, not a tunnel.

The button is rendered only while no endpoint operation is running, so it cannot be
pressed twice, and it disappears while the operation is in flight.

### 3. Progress instead of silence

`GETTING_KEY` is a distinct operation from `SCANNING`. While it runs the card says:

> "Opening a carrier to ask for this phone's key…"

and the engine's own progress is mirrored to `EngineLog` (`asking Cloudflare for an
identity through psiphon, on request`) so the diagnostics report carries it.

### 4. An empty result is a result

Previously an empty list and a crash looked the same. An empty search now reads as
finished-and-nothing-answered, with its own advice, and is not styled as an error.

### 5. A hardcoded English string removed

The success message was built in Kotlin —
`"${endpoints.size} validated endpoint${…} found"` — in a Persian-capable app. It is now a
`<plurals>` resource, translated in both locales.

## Responsiveness

| Change | Effect |
| --- | --- |
| `versionOrNull()` moved into `remember` | one JNI crossing per process instead of one per recomposition |
| Update check throttled to once per process | removes an HTTPS fetch **and** a 40 MB APK archive walk from every connect and reconnect |

## RTL / Persian verification

The new strings were added to **both** `values/strings.xml` and `values-fa/strings.xml`.

One real bug was caught while doing this, by compiling the resources rather than trusting
them: two of the new English strings contained **unescaped apostrophes**, which `aapt2`
rejects:

```
app/src/main/res/values/strings.xml:374: error: unescaped apostrophe in string
app/src/main/res/values/strings.xml:377: error: unescaped apostrophe in string
```

A third problem surfaced the same way — `msg_scan_found` had been defined twice, once as a
`<string>` and once as `<plurals>`, which AAPT cannot extract. Both were fixed before the
build went green.

**Persian rendering of the new strings is not verified on a device**: the emulator ran in
English for this session. The strings exist and are formatted correctly; the layout was
not re-checked in RTL for the new button.

## What did not change

- No animation was added, and no loading spinner was made prettier.
- No new dependency, no design change, no new screen.
- The existing visual language — cards, `OutlineButton`, `SectionLabel`, the `Data`
  monospace style — was kept exactly as it is.

## Known gaps

1. **No elapsed-time counter.** The card says what is happening but not how long it has
   been. A 20-second silence with no clock is still silence with no clock.
2. **The button is only on the endpoint screen.** A user who has not found it will not
   know it exists.
3. **No skeleton for the results list.** The card is empty while scanning, rather than
   showing placeholder rows.
4. **Progress is per-phase, not per-endpoint.** The engine probes candidates; nothing
   surfaces that count.