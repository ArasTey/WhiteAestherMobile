package com.whitedns.whiteaesther

import android.app.Application
import android.os.FileObserver
import androidx.annotation.StringRes
import com.whitedns.whiteaesther.core.AppLocale
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whitedns.whiteaesther.core.ChainController
import com.whitedns.whiteaesther.core.ChainNode
import com.whitedns.whiteaesther.core.EndpointScanResult
import com.whitedns.whiteaesther.core.MoatClient
import com.whitedns.whiteaesther.core.TorBridges
import com.whitedns.whiteaesther.core.NativeAetherBridge
import com.whitedns.whiteaesther.core.PsiphonConfig
import com.whitedns.whiteaesther.data.Carrier
import com.whitedns.whiteaesther.data.AddressReporter
import com.whitedns.whiteaesther.data.AppRelease
import com.whitedns.whiteaesther.data.AppUpdateManager
import com.whitedns.whiteaesther.data.UpdateDownload
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.EndpointMode
import com.whitedns.whiteaesther.data.EndpointScanOutcome
import com.whitedns.whiteaesther.data.EndpointScanReporting
import com.whitedns.whiteaesther.data.EngineMode
import com.whitedns.whiteaesther.data.TorBridge
import com.whitedns.whiteaesther.data.TunnelProtocol
import com.whitedns.whiteaesther.data.SettingsRepository
import com.whitedns.whiteaesther.data.AutoPlanner
import com.whitedns.whiteaesther.data.NetworkKey
import com.whitedns.whiteaesther.data.UpdateChecker
import com.whitedns.whiteaesther.service.NetworkIdentity
import com.whitedns.whiteaesther.service.AetherVpnService
import com.whitedns.whiteaesther.service.EngineLog
import com.whitedns.whiteaesther.service.EngineStage
import com.whitedns.whiteaesther.service.LogLevel
import com.whitedns.whiteaesther.service.EngineStatusStore
import com.whitedns.whiteaesther.service.TrafficMeter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Something the bridge fetch has to say, and whether it is a fault.
 *
 * Tor answering "nothing recommended here" is not a failure and must not be
 * dressed as one; failing to reach Tor at all is.
 */
data class BridgeMessage(val text: String, val isError: Boolean)

/** Something to tell the user about their identity, after an export or import. */
data class IdentityMessage(val text: String, val isError: Boolean = false)

enum class EndpointOperation {
    SCANNING,
    TESTING,
    CANCELLING,

    /**
     * Buying the engine's identity through a carrier, before any search.
     *
     * Not [SCANNING]: nothing is being scanned yet, and telling the user
     * otherwise would make a search that has not started look like one that is
     * going badly.
     */
    GETTING_KEY,
}

data class EndpointScannerState(
    val operation: EndpointOperation? = null,
    val results: List<EndpointScanResult> = emptyList(),
    val message: String? = null,
    val error: String? = null,
    /** What the engine's words turned out to mean. See [EndpointScanReporting]. */
    val outcome: EndpointScanOutcome? = null,
)

/**
 * What the running chain has to say about its nodes.
 *
 * [available] is separate from an empty list on purpose. No nodes because the
 * chain is not running, and no nodes because the subscription returned none, are
 * different problems and the screen says different things about them.
 */
data class ChainState(
    val available: Boolean = false,
    val nodes: List<ChainNode> = emptyList(),
    val selected: String? = null,
    val busy: Boolean = false,
    /**
     * How far a delay run has got, as done to total.
     *
     * A thousand nodes take minutes however they are batched, and "Testing…"
     * with no number is indistinguishable from a run that has died.
     */
    val testProgress: Pair<Int, Int>? = null,
    val error: String? = null,
    /** The engine is running a configuration the settings no longer describe. */
    val stale: Boolean = false,
)

/**
 * The address seen on each side of the tunnel.
 *
 * Both nullable and for different reasons. [real] is absent until the app has
 * been open while disconnected long enough to look it up; [tunnel] is absent
 * whenever no session is carrying traffic.
 */
data class AddressPair(
    val real: String? = null,
    val tunnel: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    /**
     * A string resource, in whatever language the app is set to.
     *
     * These messages are read on screen, so they follow the setting rather than
     * the phone. The context an AndroidViewModel holds is the application's,
     * which the activity's locale wrapping never touches -- so it is wrapped
     * here on the way past.
     */
    private fun say(@StringRes id: Int, vararg args: Any): String =
        AppLocale.wrap(getApplication<Application>()).getString(id, *args)

    private val repository = SettingsRepository(application)
    // Reaches the same mihomo the service is running: there is one per process.
    private val chain = ChainController(application)

    val settings = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )

    /**
     * Whether [settings] holds what is on disk yet, or still the placeholder.
     *
     * DataStore cannot be read synchronously, so the first value every screen
     * sees is a default that agrees with nothing the user has chosen. Most of
     * the interface can live with that for a frame. Anything that acts on a
     * setting rather than drawing it cannot: the placeholder says the language
     * is System, and an activity built for Persian read that as a change and
     * rebuilt itself, only to be told the same thing again by the next
     * placeholder -- a loop that never reached the real value.
     */
    val settingsLoaded: StateFlow<Boolean> = repository.settings
        .map { true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )
    val engineStatus = EngineStatusStore.status
    private val mutableEndpointScannerState = MutableStateFlow(EndpointScannerState())
    val endpointScannerState = mutableEndpointScannerState.asStateFlow()
    private var updateJob: Job? = null
    private var endpointJob: Job? = null
    private var realAddressJob: Job? = null
    private var bridgeJob: Job? = null

    /** Whether a bridge fetch is in flight, for the button that started it. */
    private val mutableBridgesFetching = MutableStateFlow(false)
    val bridgesFetching = mutableBridgesFetching.asStateFlow()

    /**
     * What the last bridge fetch had to say, and whether it went wrong.
     *
     * The two are separate because they read differently. "Tor recommends
     * nothing for your country" is Tor saying this network needs no help, which
     * is true of most of the world; painting that red tells the user something
     * broke when nothing did.
     */
    private val mutableBridgesMessage = MutableStateFlow<BridgeMessage?>(null)
    val bridgesMessage = mutableBridgesMessage.asStateFlow()

    /**
     * The exit countries Psiphon has said it has.
     *
     * Read from what the Psiphon process last wrote, because it is another
     * process. Empty until tunnel-core has run once and said what it has,
     * which is honest: nothing knows what Psiphon has until Psiphon says.
     */
    private val mutablePsiphonRegions = MutableStateFlow(emptyList<String>())
    val psiphonRegions = mutablePsiphonRegions.asStateFlow()

    /**
     * Notices the Psiphon process writing the list, and re-reads it.
     *
     * Until this existed the only trigger was a change of [EngineStage], and
     * tunnel-core reports its countries mid-session -- so the answer landed on
     * disk while the screen was open and the picker went on showing whatever it
     * had read when the stage last moved, often nothing at all.
     *
     * The directory rather than the file: the writer renames a temporary over
     * it, and an observer holding the old file would be watching an inode
     * nothing writes to again.
     */
    @Suppress("DEPRECATION")
    private val regionWatch = object : FileObserver(
        PsiphonConfig.dataDirectory(application).absolutePath,
        MOVED_TO or CLOSE_WRITE,
    ) {
        override fun onEvent(event: Int, path: String?) {
            if (path != null && path != REGIONS_FILE) return
            readPsiphonRegions()
        }
    }.also { runCatching { it.startWatching() } }

    // Seeded with what the build can do, rather than waiting for a refresh
    // that only happens once connected. Whether the library is present is known
    // from the start, and the screen has to say so before the user configures
    // something that can never run.
    private val mutableIdentityMessage = MutableStateFlow<IdentityMessage?>(null)
    val identityMessage = mutableIdentityMessage.asStateFlow()

    /**
     * The address the internet sees, on each side of the tunnel.
     *
     * Two separate lookups rather than one refreshed: the address without the
     * tunnel is only ever read while there is no tunnel. It is re-read each
     * time the app is idle, because a phone changes networks and the answer
     * changes with them, but never while a session is up -- asking then would
     * push the user's real address out past the thing hiding it.
     */
    private val mutableAddresses = MutableStateFlow(AddressPair())
    val addresses = mutableAddresses.asStateFlow()

    val traffic = TrafficMeter.sample

    /**
     * A newer release, once one has been seen. Null the rest of the time.
     */
    private val mutableUpdate = MutableStateFlow<UpdateChecker.Available?>(null)
    val update = mutableUpdate.asStateFlow()

    private val updates = AppUpdateManager(application)

    /**
     * The release this phone could install, once one has been found.
     *
     * Separate from [update], which only knows a version is out. This one has
     * been checked against what is installed -- same package, newer version,
     * this architecture -- and carries the assets to fetch.
     */
    private val mutableInstallable = MutableStateFlow<AppRelease?>(null)

    /**
     * Whether the update check has already run this process.
     *
     * A release cannot appear and the installed APK cannot change its
     * architecture while the app is running, so a second check in the same
     * process can only repeat work.
     */
    private var installableChecked = false

    /**
     * Whether this phone already holds a key the engine can use.
     *
     * Null until it has been asked. The question is answered from the engine's
     * own store, so it is a fact rather than a guess -- and it is the difference
     * between showing someone a step they do not need and hiding one they do.
     */
    private val mutableHasKey = MutableStateFlow<Boolean?>(null)
    val hasKey: StateFlow<Boolean?> = mutableHasKey.asStateFlow()

/**
     * The engine's configuration for [settings], with the two names it refuses
     * resolved, and the only place either is resolved.
     *
     * The bridge rejects `transport: "auto"` outright and `parse()` runs before
     * anything is read, so a call that hands it Automatic gets a refusal rather
     * than an answer. Three call sites had each grown their own workaround and
     * two of them forgot, which is what left `hasIdentity` reporting "no key" on
     * a phone that had one -- so the app asked for a key it was holding, and
     * pressing the button returned it instantly from disk.
     *
     * [scanFirstFraming] rather than a fixed answer, because Automatic resolves
     * per network and this is the same question the scanner and the key request
     * ask, asked in the same place.
     */
    private fun engineConfigFor(settings: AppSettings): AppSettings = settings.copy(
        // An endpoint is irrelevant to whether a key exists, and a pinned one
        // would make the bridge ask about a peer instead.
        endpointMode = EndpointMode.AUTOMATIC,
        customEndpoint = "",
        transport = if (settings.transport.isAutomatic) scanFirstFraming() else settings.transport,
    )

    /** Re-asks the engine. Cheap: a read of the store, no network. */
    fun refreshHasKey(settings: AppSettings) {
        viewModelScope.launch {
            mutableHasKey.value = withContext(Dispatchers.IO) {
                runCatching {
                    NativeAetherBridge.hasIdentity(
                        engineConfigFor(settings).toNativeJson(getApplication()),
                    )
                }.getOrDefault(false)
            }
        }
    }
    val installable = mutableInstallable.asStateFlow()

    private val mutableDownload = MutableStateFlow<UpdateDownload>(UpdateDownload.Idle)
    val updateDownload = mutableDownload.asStateFlow()

    /**
     * Whether an update may be fetched right now, rather than opened in a
     * browser.
     *
     * The download does not go through this app's proxy — the downloader is a
     * system service, outside this process — so only a whole-device tunnel
     * carries it. Anywhere else the user gets the release page, which is what
     * they had before.
     */
    val canInstallInPlace: StateFlow<Boolean> = combine(
        repository.settings,
        EngineStatusStore.status,
        mutableInstallable,
    ) { settings, status, release ->
        release != null && updates.mayDownload(
            coverage = settings.coverage(),
            connected = status.stage == EngineStage.CONNECTED,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    /**
     * Fetches the release and follows it until it has arrived or failed.
     *
     * The download itself is the system downloader's, so it survives this
     * process; what is watched here is only its progress. Verification happens
     * where the file lands, in [AppUpdateManager], not here.
     */
    fun downloadUpdate() {
        val release = mutableInstallable.value ?: return
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            mutableDownload.value = UpdateDownload.Running(0L, release.apk?.size ?: 0L)
            val started = runCatching { updates.download(release) }
            started.exceptionOrNull()?.let { error ->
                mutableDownload.value = UpdateDownload.Failed(
                    error.message ?: say(R.string.the_update_could_not_be_verified),
                )
                return@launch
            }
            while (true) {
                val state = runCatching { updates.progress(release) }
                    .getOrElse {
                        UpdateDownload.Failed(
                            it.message ?: say(R.string.the_update_could_not_be_verified),
                        )
                    }
                mutableDownload.value = state
                if (state !is UpdateDownload.Running) return@launch
                delay(UPDATE_POLL_MS)
            }
        }
    }

    /**
     * Whether this build updates itself at all.
     *
     * False in an F-Droid build, where F-Droid does it. The interface needs the
     * difference: "not right now, because of your coverage" and "never, this
     * build does not do that" are not the same thing to explain.
     */
    val updatesInPlace: Boolean get() = updates.selfUpdates

    fun cancelUpdateDownload() {
        updateJob?.cancel()
        updateJob = null
        viewModelScope.launch {
            updates.cancel()
            mutableDownload.value = UpdateDownload.Idle
        }
    }

    /** Remembers that the user said no, so the same version stops asking. */
    fun skipUpdate() {
        mutableInstallable.value?.version?.let(updates::skip)
        cancelUpdateDownload()
        mutableInstallable.value = null
        dismissUpdate()
    }

    /**
     * Whether the user has switched IPv6 off.
     *
     * The setting names what the tunnel scans, but a screen that shows an IPv6
     * address while the switch says IPv4 only reads as the switch doing
     * nothing, so the lookup follows it too.
     *
     * Read from the repository rather than from [settings]. That flow is a
     * stateIn holding AppSettings() until DataStore answers, and this runs from
     * onResume -- early enough to see the placeholder, whose dualStack is true.
     * The address lookup was therefore deciding by the default instead of by
     * the user, and kept showing the IPv6 address it was meant to discard.
     */
    private suspend fun ipv4Only(): Boolean = !repository.settings.first().dualStack

    /**
     * Whether a second hop is carrying this session's traffic.
     *
     * When it is, the exit belongs to the chain's node rather than to
     * Cloudflare, and nothing reachable from this process can measure it.
     */
    private suspend fun chainCarriesTraffic(): Boolean {
        val current = repository.settings.first()
        return current.chain.enabled && current.mode == EngineMode.TUN
    }

    /** Stops asking about this version. */
    fun dismissUpdate() {
        val available = mutableUpdate.value ?: return
        mutableUpdate.value = null
        viewModelScope.launch { UpdateChecker.dismiss(getApplication(), available.version) }
    }


    init {
        watchConnectionForAddresses()
        sampleTrafficWhileConnected()
        viewModelScope.launch {
            // Still on every change of stage, as well as on the watch above: a
            // file written while this screen was not running produces no event
            // to catch up on, and the first read has to come from somewhere.
            EngineStatusStore.status
                .map { it.stage }
                .distinctUntilChanged()
                .collect { readPsiphonRegions() }
        }
    }

    private fun readPsiphonRegions() {
        viewModelScope.launch {
            val known = withContext(Dispatchers.IO) {
                PsiphonConfig.availableRegions(getApplication())
            }
            // Only forward. The writer already refuses to record an empty
            // answer; this is the same rule for a read that raced a rename.
            if (known.isNotEmpty()) mutablePsiphonRegions.value = known
        }
    }

    /**
     * Reads the real address now, while nothing is carrying traffic.
     *
     * Called when the app is opened and idle. A user who installs, connects
     * immediately and never opens the app while disconnected simply has no
     * before-figure, which is better than fetching one through the tunnel and
     * labelling the tunnel's own exit as theirs.
     */
    fun captureRealAddressIfIdle() {
        if (EngineStatusStore.status.value.stage != EngineStage.IDLE) return
        // One at a time. onResume and the idle transition both arrive here, and
        // often within the same moment; two fetches would race to write one
        // field and the loser's answer would be the one left on screen.
        if (realAddressJob?.isActive == true) return
        realAddressJob = viewModelScope.launch {
            val ipv4Only = ipv4Only()
            val stored = AddressReporter.realAddress(getApplication(), ipv4Only)
            // Shown first so the row is filled while the network is asked, then
            // replaced by whatever the network answers.
            if (stored != null && mutableAddresses.value.real != stored) {
                mutableAddresses.value = mutableAddresses.value.copy(real = stored)
            }
            // Asked again every time, not only when nothing is stored. The
            // stored answer was true of the network that produced it: move from
            // wifi to mobile data, or let the ISP hand out a new address, and
            // it becomes a confident lie that nothing in the app can clear --
            // the first reading a device ever took would outlive every network
            // it went on to join. Refreshing is unsafe only while a tunnel is
            // carrying traffic, and this returns above when one is.
            val fresh = AddressReporter.captureRealAddress(getApplication(), ipv4Only)
            // Offline, or a blocked trace, leaves the stored answer standing
            // rather than blanking a field the user was reading.
            mutableAddresses.value = mutableAddresses.value.copy(real = fresh ?: stored)
        }
    }

    private fun watchConnectionForAddresses() {
        viewModelScope.launch {
            EngineStatusStore.status
                .map { it.stage }
                .distinctUntilChanged()
                .collect { stage ->
                    when (stage) {
                        EngineStage.CONNECTED -> {
                            mutableAddresses.value = mutableAddresses.value.copy(tunnel = null)
                            // With the exit chain running this process is kept
                            // off its own interface -- deliberately, or the
                            // engine's and mihomo's own sockets would be
                            // captured by the tunnel they are building. A probe
                            // from here therefore leaves by the physical
                            // network and reports the address the tunnel exists
                            // to hide. It said "seen by websites" over the
                            // user's real IP.
                            val carrierPort = EngineStatusStore.status.value.carrierSocksPort
                            val exit = when {
                                // The chain's node is the exit, and nothing
                                // reachable from this process can measure it.
                                chainCarriesTraffic() -> null
                                // A carrier, asked through its own listener.
                                // Measuring it the ordinary way reports this
                                // phone's address under a heading that says the
                                // opposite, because this process is excluded
                                // from the interface on purpose -- which is
                                // exactly what it did before this existed.
                                carrierPort != null -> AddressReporter.carrierAddress(carrierPort)
                                else -> AddressReporter.tunnelAddress(ipv4Only())
                            }
                            mutableAddresses.value = mutableAddresses.value.copy(tunnel = exit)
                            // Only here, and both of them. Asking GitHub from
                            // an unprotected socket would tell the network this
                            // device runs a circumvention tool; inside the
                            // tunnel it is just more session traffic.
                            //
                            // Once per session at most. This ran on every
                            // CONNECTED transition -- so every connect and
                            // every reconnect -- and each run fetched a JSON
                            // release listing over a fresh TLS connection and
                            // then opened the installed ~40 MB APK as a ZipFile
                            // to read its architecture back out. That is a
                            // large HTTPS round trip and a full archive
                            // central-directory walk to answer a question whose
                            // answer cannot have changed while the process is
                            // alive. UpdateChecker already throttles itself to
                            // once a day; this one did not throttle at all.
                            if (!installableChecked) {
                                installableChecked = true
                                mutableInstallable.value = runCatching {
                                    updates.check(acceptPrereleases = false)
                                }.getOrNull()
                                mutableUpdate.value = UpdateChecker.check(
                                    getApplication(),
                                    BuildConfig.VERSION_NAME,
                                )
                            }
                        }
                        EngineStage.IDLE -> {
                            mutableAddresses.value = mutableAddresses.value.copy(tunnel = null)
                            captureRealAddressIfIdle()
                        }
                        else -> mutableAddresses.value = mutableAddresses.value.copy(tunnel = null)
                    }
                }
        }
    }

    /**
     * One reading a second, and only while something is running.
     *
     * Sampling a stopped tunnel would burn a wake-up per second to report
     * zero, which is the sort of thing that turns into a battery complaint.
     */
    private fun sampleTrafficWhileConnected() {
        viewModelScope.launch {
            while (true) {
                if (EngineStatusStore.status.value.stage == EngineStage.CONNECTED) {
                    withContext(Dispatchers.IO) { TrafficMeter.sampleNow() }
                }
                delay(TRAFFIC_SAMPLE_MS)
            }
        }
    }

    private val mutableChainState = MutableStateFlow(ChainState(available = chain.isAvailable))
    val chainState = mutableChainState.asStateFlow()
    private var chainJob: Job? = null

    fun save(settings: AppSettings) {
        viewModelScope.launch { repository.save(settings) }
    }

    /**
     * Produces the identity file the user saves before a reinstall.
     *
     * Returns null when there is nothing to export yet, so the caller does not
     * open a file picker for an empty file.
     */
    /**
     * Which framing a scan should sweep first.
     *
     * The same question the connect path asks, answered in the same place. The
     * scanner has no memory of its own -- what connected last is the service's
     * to know -- so it asks with what it has, which is the kind of network the
     * phone is on. Getting only that half right still beats a third rule.
     */
    /**
     * The framings worth searching after the first one found nothing.
     *
     * H2 probes over TCP and H3 over QUIC, because the MASQUE prober takes its
     * transport from the framing it is handed. So one search covers one of
     * them, and a network that blocks UDP returns nothing at all for the other
     * -- which is what Iranian mobile users were seeing reported as an empty
     * network. Hence the sweep rather than a single answer.
     *
     * Nested MASQUE probes over QUIC, like H3, because its hops are H3 unless
     * the whole profile is H2, so the sweep worth making after it is the TCP
     * one over the same edges.
     *
     * WireGuard and WARP-in-WARP have no second framing. Their endpoints are
     * their own, so offering MASQUE addresses here would list addresses the
     * chosen protocol cannot use.
     */
    private fun sweepOf(settings: AppSettings): List<TunnelProtocol> =
        when (settings.transport) {
            TunnelProtocol.H3 -> listOf(TunnelProtocol.H2)
            TunnelProtocol.H2 -> listOf(TunnelProtocol.H3)
            TunnelProtocol.MASQUE_IN_MASQUE -> listOf(TunnelProtocol.H2)
            TunnelProtocol.WIREGUARD, TunnelProtocol.WARP_IN_WARP -> emptyList()
            // Automatic is resolved before this is asked, so reaching here
            // means the planner answered with something that is not a framing.
            // Sweeping both is still the useful move.
            TunnelProtocol.AUTO -> listOf(TunnelProtocol.H3, TunnelProtocol.H2)
        }

    private fun scanFirstFraming(): TunnelProtocol {
        val cellular = runCatching {
            NetworkKey.isCellular(NetworkIdentity.current(getApplication()).key.orEmpty())
        }.getOrDefault(false)
        return when (AutoPlanner.framingOrder(provenFraming = null, onMobileData = cellular).first()) {
            "h2" -> TunnelProtocol.H2
            else -> TunnelProtocol.H3
        }
    }

    fun exportIdentity(): String? {
        val result = NativeAetherBridge.exportIdentity(identityConfigPath())
        result.exceptionOrNull()?.let { error ->
            mutableIdentityMessage.value = IdentityMessage(
                error.message ?: say(R.string.msg_no_identity_yet),
                isError = true,
            )
            return null
        }
        return result.getOrNull()
    }

    /**
     * Restores an identity, refusing while a tunnel is up.
     *
     * Swapping the identity under a running session would leave the engine
     * carrying traffic for a device it no longer has the keys for, and the
     * failure would arrive much later than the cause.
     */
    fun importIdentity(payload: String) {
        if (EngineStatusStore.status.value.stage != EngineStage.IDLE &&
            EngineStatusStore.status.value.stage != EngineStage.ERROR
        ) {
            mutableIdentityMessage.value =
                IdentityMessage(say(R.string.msg_disconnect_before_import), isError = true)
            return
        }
        val result = NativeAetherBridge.importIdentity(identityConfigPath(), payload)
        mutableIdentityMessage.value = if (result.ok) {
            IdentityMessage(say(R.string.msg_identity_imported))
        } else {
            IdentityMessage(result.error ?: say(R.string.msg_not_an_identity), isError = true)
        }
    }

    /** Reports how saving the backup went, since only the activity can know. */
    fun reportIdentityWrite(error: String?) {
        mutableIdentityMessage.value = if (error == null) {
            IdentityMessage(say(R.string.msg_identity_saved))
        } else {
            IdentityMessage(error, isError = true)
        }
    }

    fun clearIdentityMessage() {
        mutableIdentityMessage.value = null
    }

    /** The base path the engine derives every identity file from. */
    private fun identityConfigPath(): String =
        java.io.File(getApplication<Application>().filesDir, "aether.toml").absolutePath

    /**
     * Reads the node list back from the running chain.
     *
     * There is no list to read when it is not running. The nodes come from a
     * subscription mihomo has fetched and parsed, and fetching it early -- before
     * the tunnel exists -- would send the request over the local network in the
     * clear, which is the one thing dialling through the tunnel exists to avoid.
     */
    fun refreshChainNodes(settings: AppSettings) {
        if (chainJob?.isCompleted == false) return
        chainJob = viewModelScope.launch {
            mutableChainState.value = mutableChainState.value.copy(busy = true, error = null)
            // Editing a subscription does not reconfigure a chain that is
            // already running, so the engine would answer with the previous
            // one's nodes. Reporting them as the new subscription's is what
            // reads as "deleting it did nothing".
            if (!chain.isRunningConfigCurrent(settings.chainForService())) {
                mutableChainState.value = ChainState(available = chain.isAvailable, stale = true)
                return@launch
            }
            val reported = withContext(Dispatchers.IO) { chain.nodes() }
            mutableChainState.value = ChainState(
                available = chain.isAvailable,
                nodes = reported.nodes,
                selected = reported.selected,
            )
        }
    }

    /**
     * Stops a delay run and keeps whatever it measured.
     *
     * Needed once a subscription can carry a thousand nodes: the run is minutes
     * long, and without this the only way out is to leave the screen and hope.
     */
    fun cancelChainTests() {
        chainJob?.cancel()
        chainJob = null
        mutableChainState.value = mutableChainState.value.copy(busy = false, testProgress = null)
        viewModelScope.launch {
            val reported = withContext(Dispatchers.IO) { chain.nodes() }
            mutableChainState.value = mutableChainState.value.copy(
                nodes = reported.nodes,
                selected = reported.selected,
            )
        }
    }

    /** Switches the live chain, and remembers the choice for the next connect. */
    fun selectChainNode(settings: AppSettings, node: String) {
        if (chainJob?.isCompleted == false) return
        // Moved before anything blocking. Saving, telling mihomo and reading the
        // list back are three round trips, and until they finished the row the
        // user had just tapped looked unchanged -- which reads as a tap that
        // did not register, so people tap again.
        mutableChainState.value = mutableChainState.value.copy(selected = node)
        chainJob = viewModelScope.launch {
            // Saved first. If the engine refuses the switch the preference is
            // still what the user asked for, and the next connect honours it --
            // better than a screen that silently reverts.
            repository.save(settings.copy(chain = settings.chain.copy(node = node)))
            val failure = withContext(Dispatchers.IO) { chain.select(node) }
            val reported = withContext(Dispatchers.IO) { chain.nodes() }
            mutableChainState.value = ChainState(
                available = chain.isAvailable,
                nodes = reported.nodes,
                selected = reported.selected,
                error = failure,
            )
        }
    }

    /**
     * Measures every node.
     *
     * The results come back through mihomo's event stream and land in the log, so
     * this waits before re-reading rather than expecting an answer here.
     */
    /**
     * Measures only the nodes the user ticked.
     *
     * Worth having separately on a large subscription: testing fifty takes the
     * best part of half a minute, and somebody comparing three of them should
     * not have to wait for the other forty-seven.
     */
    fun testChainNodes(only: List<String>) {
        if (chainJob?.isCompleted == false) return
        val supported = mutableChainState.value.nodes.filter { it.supported }.map { it.name }
        val names = only.filter { it in supported }
        if (names.isEmpty()) return
        runDelayTests(names)
    }

    fun testChainNodes() {
        if (chainJob?.isCompleted == false) return
        // Unsupported nodes are skipped rather than measured. A REALITY node
        // fails the handshake, so its delay test times out and reports the node
        // as slow when the engine simply cannot speak to it.
        val names = mutableChainState.value.nodes.filter { it.supported }.map { it.name }
        if (names.isEmpty()) return
        runDelayTests(names)
    }

    /**
     * Measures the nodes a handful at a time, showing results as they arrive.
     *
     * All at once was the bug: fifty delay tests fired together are fifty
     * concurrent requests through one tunnel, and the tunnel saturates. The
     * first few answered and the rest timed out, so most of the list came back
     * blank however long the wait was afterwards.
     *
     * Reading the list between batches also means the pings appear as they are
     * measured rather than all at the end, which on a long list is the
     * difference between progress and a frozen screen.
     */
    private fun runDelayTests(names: List<String>) {
        chainJob = viewModelScope.launch {
            var done = 0
            mutableChainState.value = mutableChainState.value.copy(
                busy = true,
                error = null,
                testProgress = 0 to names.size,
            )
            for (batch in names.chunked(DELAY_TEST_BATCH)) {
                withContext(Dispatchers.IO) { chain.testNodes(batch) }
                delay(DELAY_TEST_BATCH_MS)
                done += batch.size
                // Reading the list back is a JNI call returning every proxy as
                // JSON, so on a thousand nodes it is the expensive half of the
                // loop. Done every few batches rather than every one: often
                // enough to watch pings arrive, rarely enough not to dominate.
                val partial = if (done % (DELAY_TEST_BATCH * REFRESH_EVERY) == 0) {
                    withContext(Dispatchers.IO) { chain.nodes() }
                } else {
                    null
                }
                mutableChainState.value = mutableChainState.value.copy(
                    nodes = partial?.nodes ?: mutableChainState.value.nodes,
                    selected = partial?.selected ?: mutableChainState.value.selected,
                    testProgress = done to names.size,
                )
            }
            val reported = withContext(Dispatchers.IO) { chain.nodes() }
            mutableChainState.value = ChainState(
                available = chain.isAvailable,
                nodes = reported.nodes,
                selected = reported.selected,
            )
        }
    }

    /**
     * Forgets the endpoint entirely and looks for a new one.
     *
     * Three separate things remember an endpoint, and clearing one of them is
     * what makes this look like it did nothing: the pinned address in settings,
     * the results still listed on screen, and the transport the service saw
     * work last. A pin survives the network it was found on -- an address that
     * answered on home wifi is just an address that times out on mobile data --
     * and with fallback off there is nothing to move on to, so the user reads a
     * dead pin as the app being broken.
     *
     * The scan starts here rather than being left to the next connect so that
     * the reset has something to show for itself.
     */
    fun resetEndpoint(settings: AppSettings) {
        val cleared = settings.withoutPinnedEndpoint()
        save(cleared)
        AetherVpnService.forgetLastGoodTransport(getApplication())
        mutableEndpointScannerState.value = EndpointScannerState()
        scanEndpoints(cleared)
    }

    /**
     * Asks Tor which bridges to use here, and saves them.
     *
     * Through the running carrier when there is one. That is the part Tor
     * Browser cannot do and this app can: `bridges.torproject.org` is blocked
     * in most of the places its answer is wanted, and this app already has two
     * other ways out of exactly those places. Asked directly only when nothing
     * is up, which is the case where direct is likely to work anyway.
     *
     * The country is the network's, not the exit's. Asked through a tunnel, the
     * service would otherwise be told about Singapore and answer about
     * Singapore, which is useless to somebody in Iran.
     */
    /**
     * The country the fetch will ask about unless told otherwise.
     *
     * Shown so it can be corrected. It comes from the network operator, which
     * is right for somebody at home and wrong for somebody testing for a place
     * they are not -- and Tor answers per country, so asking as the wrong one
     * gets a correct answer to the wrong question.
     */
    fun detectedCountry(): String = MoatClient.country(getApplication())

    fun fetchBridges(settings: AppSettings, country: String = "") {
        if (bridgeJob?.isActive == true) return
        mutableBridgesMessage.value = null
        mutableBridgesFetching.value = true
        bridgeJob = viewModelScope.launch {
            val context = getApplication<Application>()
            val through = EngineStatusStore.status.value
                .takeIf { it.stage == EngineStage.CONNECTED }
                ?.carrierSocksPort
            val asked = country.trim().lowercase().takeIf { it.length == 2 }
                ?: MoatClient.country(context)
            val result = MoatClient.recommendations(asked, through)
            mutableBridgesFetching.value = false

            val recommendations = result.getOrElse { error ->
                EngineLog.record(LogLevel.WARN, "bridges", error.message ?: "fetch failed")
                mutableBridgesMessage.value =
                    BridgeMessage(say(R.string.tor_bridges_failed), isError = true)
                return@launch
            }
            // The first one Tor lists, because it lists them in the order it
            // recommends trying them for that country -- and the app can only
            // run one transport at a time.
            // Two different answers that a single failure message would blur.
            // An empty list is Tor saying this network needs no help -- which is
            // true of most of the world and is not a fault to report as one.
            val best = recommendations.firstOrNull { it.lines.isNotEmpty() }
            if (best == null) {
                mutableBridgesMessage.value = BridgeMessage(
                    say(R.string.tor_bridges_none_recommended, asked.uppercase()),
                    isError = false,
                )
                return@launch
            }
            EngineLog.record(
                LogLevel.INFO,
                "bridges",
                "tor recommends ${best.transport} for $asked: ${best.lines.size} bridges",
            )
            save(
                settings.copy(
                    torBridge = TorBridge.CUSTOM,
                    torBridges = best.lines.joinToString("\n"),
                ),
            )
        }
    }

    fun clearBridgesMessage() {
        mutableBridgesMessage.value = null
    }

    fun scanEndpoints(settings: AppSettings) {
        if (!canRunEndpointOperation()) return
        // No key, no search. A scan without an identity cannot succeed, and
        // running one anyway is what left this screen reporting "no endpoints"
        // on a network whose real problem was a missing registration. The check
        // is here as well as on the button so nothing else can start one.
        if (mutableHasKey.value != true) {
            mutableEndpointScannerState.value = EndpointScannerState(
                results = mutableEndpointScannerState.value.results,
                message = say(R.string.msg_key_needed_first),
            )
            return
        }
        endpointJob = viewModelScope.launch {
            mutableEndpointScannerState.value = EndpointScannerState(
                operation = EndpointOperation.SCANNING,
                results = mutableEndpointScannerState.value.results,
                message = say(R.string.msg_scanning_routes, say(settings.transport.probedAs.label)),
            )
val base = settings.copy(endpointMode = EndpointMode.AUTOMATIC, customEndpoint = "")
            // The transports to search, in the order to search them.
            //
            // The bridge takes a real transport and refuses "auto" outright, so
            // the ladder is built here where the planner can be asked. On the
            // default it is WireGuard first, for the reason the race gives it
            // first: it has a fixed peer and does not search for an edge at all,
            // and every rung behind it is a MASQUE framing that has to find one.
            // Then the framings, in the order this network suggests.
            //
            // De-duplicated across the whole thing, because the sweep is
            // written in both directions: on a network where the planner leads
            // with H3, "H3 then H2" and the sweep would hand back H3 twice,
            // and searching one framing for a second minute is the slowest way
            // to find nothing.
            //
            // A transport the user fixed is searched on its own, as they set it.
            val ladder: List<TunnelProtocol> = if (!settings.transport.isAutomatic) {
                listOf(settings.transport)
            } else {
                (listOf(TunnelProtocol.WIREGUARD, scanFirstFraming()) + sweepOf(settings))
                    .distinct()
            }

            var result: Result<List<EndpointScanResult>> = Result.success(emptyList())
            for ((index, transport) in ladder.withIndex()) {
                if (mutableEndpointScannerState.value.operation != EndpointOperation.SCANNING) break
                val attempt = withContext(Dispatchers.IO) {
                    NativeAetherBridge.scan(base.copy(transport = transport).toNativeJson(getApplication()))
                }
                // The MASQUE prober picks TCP or UDP from the framing it is
                // handed, so one sweep searches only one of them. Carry on
                // rather than reporting an empty network.
                if (attempt.getOrNull()?.isEmpty() != false && index < ladder.lastIndex) {
                    val next = ladder[index + 1]
                    mutableEndpointScannerState.value = mutableEndpointScannerState.value.copy(
                        message = say(
                            R.string.msg_nothing_over_trying,
                            say(transport.label),
                            say(next.label),
                        ),
                    )
                    result = attempt
                    continue
                }
                result = attempt
                break
            }
            result.fold(
                onSuccess = { endpoints ->
                    if (mutableEndpointScannerState.value.operation == EndpointOperation.CANCELLING) {
                        mutableEndpointScannerState.value = EndpointScannerState(
                            results = mutableEndpointScannerState.value.results,
                            message = say(R.string.msg_scan_cancelled),
                        )
                    } else if (endpoints.isEmpty()) {
                        // The search finished and nothing answered. That is a
                        // result, and a different one from "the search broke":
                        // the network worked, there was simply no way out from
                        // here. It carries its own advice, so it is not shown as
                        // an error.
                        mutableEndpointScannerState.value = EndpointScannerState(
                            results = emptyList(),
                            message = say(R.string.msg_scan_nothing_answered),
                            outcome = EndpointScanOutcome.NOTHING_ANSWERED,
                        )
                    } else {
                        // A search that returned endpoints means an identity was
                        // already there. Say so, so the key step stops being
                        // offered to someone who does not need it.
                        mutableHasKey.value = true
                        mutableEndpointScannerState.value = EndpointScannerState(
                            results = endpoints,
                            message = getApplication<Application>().resources
                                .getQuantityString(
                                    R.plurals.msg_scan_found,
                                    endpoints.size,
                                    endpoints.size,
                                ),
                        )
                    }
                },
                onFailure = { error ->
                    if (mutableEndpointScannerState.value.operation == EndpointOperation.CANCELLING) {
                        mutableEndpointScannerState.value = EndpointScannerState(
                            results = mutableEndpointScannerState.value.results,
                            message = say(R.string.msg_scan_cancelled),
                        )
                    } else if (mutableEndpointScannerState.value.operation == EndpointOperation.SCANNING) {
                        // What the engine says is written for an engineer.
                        // Passed through, it reached the screen as a red URL and
                        // a socket error, inside the card that is supposed to
                        // list endpoints. Classify it and say the part a person
                        // can act on. The engine's own wording stays in the
                        // diagnostics report, where it belongs.
                        val outcome = EndpointScanReporting.classify(error.message)
                        val kept = mutableEndpointScannerState.value.results
                        mutableEndpointScannerState.value =
                            if (EndpointScanReporting.isProgress(outcome)) {
                                // Still retrying rather than failed: not an error,
                                // and not a result either. The search has not
                                // concluded.
                                EndpointScannerState(
                                    results = kept,
                                    message = say(R.string.msg_scan_still_working),
                                    outcome = outcome,
                                )
                            } else {
                                EndpointScannerState(
                                    results = kept,
                                    message = say(
                                        when (outcome) {
                                            EndpointScanOutcome.NETWORK_UNREACHABLE ->
                                                R.string.msg_scan_network_unreachable
                                            EndpointScanOutcome.NOTHING_ANSWERED ->
                                                R.string.msg_scan_nothing_answered
                                            else -> R.string.msg_scan_failed
                                        },
                                    ),
                                    // Only the engine's raw wording goes to the
                                    // error slot, and only when it said something
                                    // we do not recognise -- where hiding it would
                                    // lose the only clue there is.
                                    error = if (outcome == EndpointScanOutcome.UNKNOWN) {
                                        error.message
                                    } else {
                                        null
                                    },
                                    outcome = outcome,
                                )
                            }
                    }
                },
            )
        }
    }

    fun testEndpoint(settings: AppSettings) {
        val validationError = settings.copy(endpointMode = EndpointMode.CUSTOM_ONLY)
            .endpointValidationError()
        if (validationError != null) {
            mutableEndpointScannerState.value = EndpointScannerState(
                results = mutableEndpointScannerState.value.results,
                error = say(validationError),
            )
            return
        }
        if (!canRunEndpointOperation()) return
        endpointJob = viewModelScope.launch {
            mutableEndpointScannerState.value = EndpointScannerState(
                operation = EndpointOperation.TESTING,
                results = mutableEndpointScannerState.value.results,
                message = say(R.string.msg_testing_endpoint),
            )
            val config = settings.copy(endpointMode = EndpointMode.CUSTOM_ONLY)
                .toNativeJson(getApplication())
            val result = withContext(Dispatchers.IO) { NativeAetherBridge.testEndpoint(config) }
            result.fold(
                onSuccess = { endpoint ->
                    mutableEndpointScannerState.value = EndpointScannerState(
                        results = mutableEndpointScannerState.value.results,
                        message = "${endpoint.peer} validated in ${endpoint.rttMillis} ms",
                    )
                },
                onFailure = { error ->
                    mutableEndpointScannerState.value = EndpointScannerState(
                        results = mutableEndpointScannerState.value.results,
                        error = error.message ?: say(R.string.msg_endpoint_test_failed),
                    )
                },
            )
        }
    }

/**
     * Asks Cloudflare for this phone's key, over whatever network is there.
     *
     * That is the whole of it. The previous version checked whether one of *this
     * app's own* carriers had a SOCKS port open before making any request at
     * all, and on a phone whose VPN belongs to some other app that check is
     * always false -- so the button produced no request, only the sentence
     * "turn your VPN on first", however the user had their VPN arranged. It also
     * ran the request as a job on a service it then stopped on the very next
     * line, which cancelled that job before it did anything.
     *
     * The engine issues an ordinary request to `api.cloudflareclient.com` and
     * stores what comes back. Whether that leaves depends on the network the
     * phone is on, and if the user has turned their own VPN on it simply leaves
     * through it -- which is the ordinary way every other request on a phone
     * works, and is not this app's business to inspect.
     *
     * It stops at the key. The endpoint search is a separate press, because the
     * search is thousands of probes and is worth running on a network the user
     * has chosen, not on the one they borrowed a key through.
     */
    fun getKey(settings: AppSettings) {
        if (!canRunEndpointOperation()) return
        // Already answered: there is no key to fetch. Saying so plainly is the
        // whole response -- searching here would be the chained behaviour this
        // deliberately does not do.
        if (mutableHasKey.value == true) {
            mutableEndpointScannerState.value = EndpointScannerState(
                results = mutableEndpointScannerState.value.results,
                message = say(R.string.msg_key_already_have),
            )
            return
        }
        endpointJob = viewModelScope.launch {
            mutableEndpointScannerState.value = EndpointScannerState(
                operation = EndpointOperation.GETTING_KEY,
                results = mutableEndpointScannerState.value.results,
                message = say(R.string.msg_key_asking),
            )
// Automatic has to become a real transport first. The engine refuses
            // the name outright -- `transport must be h3, h2, wg, wiw or mim` --
            // and the service is what normally resolves it, so a call made from
            // here rather than through the service arrived with "auto" still on
            // it and was refused before a single request left the phone. That is
            // the default setting, so it was every press, not an edge case.
            //
            // Both families are registered, not just the one the user is on, so
            // that changing the protocol afterwards is not a second trip to this
            // button. They cannot be one registration: MASQUE enrols onto its
            // device with `PATCH /reg/{id}`, which overwrites the same `key`
            // field the Curve25519 public key filled, and Cloudflare is then
            // left with no WireGuard key for it. Two registrations, once per
            // install, is what "works for every protocol" actually costs.
            //
            // Each is a separate call because the engine refuses to provision
            // two at once, and the engine loads what it already holds before
            // asking for anything -- so a second press costs a round trip, not
            // another registration.
            val families = listOf(
                engineConfigFor(settings).transport,
                TunnelProtocol.WIREGUARD,
            ).distinct()
            var lastFailure: Throwable? = null
            var gotOne = false
            for (transport in families) {
                val attempt = withContext(Dispatchers.IO) {
                    NativeAetherBridge.provision(
                        settings.copy(transport = transport).toNativeJson(getApplication()),
                    )
                }
                attempt.fold(
                    onSuccess = { gotOne = true },
                    onFailure = { error -> if (lastFailure == null) lastFailure = error },
                )
            }

            if (gotOne) {
                mutableHasKey.value = true
                // Stop here. The next action is the user's: turn their own VPN
                // off, then search for endpoints on a clean network.
                mutableEndpointScannerState.value = EndpointScannerState(
                    results = mutableEndpointScannerState.value.results,
                    message = say(R.string.msg_key_ready),
                )
                return@launch
            }

            val error = lastFailure
            if (error == null) return@launch
            // A failed registration leaves a wait behind on this address, and
            // the next press is refused before any request is made. Telling a
            // user to check their connection then is wrong twice over: the
            // network is fine, and pressing again only lengthens the wait. Say
            // what is actually happening.
            val held = EndpointScanReporting.isProgress(
                EndpointScanReporting.classify(error.message),
            )
            mutableEndpointScannerState.value = EndpointScannerState(
                results = mutableEndpointScannerState.value.results,
                message = say(if (held) R.string.msg_key_waiting else R.string.msg_key_failed),
            )
        }
    }

    /** Stops a key purchase that is still in flight. */
    fun cancelKeyPreparation() {
        endpointJob?.cancel()
        NativeAetherBridge.cancelPrepare()
        mutableEndpointScannerState.value = EndpointScannerState(
            results = mutableEndpointScannerState.value.results,
            message = say(R.string.msg_scan_cancelled),
        )
    }

    fun cancelEndpointScan() {
        if (mutableEndpointScannerState.value.operation != EndpointOperation.SCANNING) return
        NativeAetherBridge.cancelScan()
        mutableEndpointScannerState.value = EndpointScannerState(
            operation = EndpointOperation.CANCELLING,
            results = mutableEndpointScannerState.value.results,
            message = say(R.string.msg_cancelling_scan),
        )
    }

    private fun canRunEndpointOperation(): Boolean {
        if (endpointJob?.isCompleted == false) return false
        if (EngineStatusStore.status.value.stage !in setOf(EngineStage.IDLE, EngineStage.ERROR)) {
            mutableEndpointScannerState.value = EndpointScannerState(
                results = mutableEndpointScannerState.value.results,
                error = say(R.string.msg_disconnect_before_scan),
            )
            return false
        }
        return true
    }

    override fun onCleared() {
        runCatching { regionWatch.stopWatching() }
        NativeAetherBridge.cancelScan()
        endpointJob?.cancel()
        chainJob?.cancel()
        super.onCleared()
    }

    private companion object {
        /** How often the system downloader is asked where it has got to. */
        const val UPDATE_POLL_MS = 700L

        /** What the Psiphon process names the file this watches for. */
        const val REGIONS_FILE = "regions.txt"

        /**
         * How many nodes are measured at once.
         *
         * Small enough that one tunnel carries them without the tests starving
         * each other, large enough that a long list does not take all evening.
         */
        const val DELAY_TEST_BATCH = 16

        /** One batch's wait, now that it is not counted per node. */
        const val DELAY_TEST_BATCH_MS = 6_000L

        /** Batches between full re-reads of the list. */
        const val REFRESH_EVERY = 4

        /** One second: fast enough to read as live, slow enough to be free. */
        const val TRAFFIC_SAMPLE_MS = 1_000L
    }
}
