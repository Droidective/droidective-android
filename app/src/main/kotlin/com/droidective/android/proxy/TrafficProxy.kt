package com.droidective.android.proxy

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.droidective.android.droidective
import com.droidective.core.device.SystemValues
import com.droidective.core.device.ValueTable
import com.droidective.core.net.proxy.Breakpoint
import com.droidective.core.net.proxy.ConnectionOwners
import com.droidective.core.net.proxy.Flow
import com.droidective.core.net.proxy.FlowOwners
import com.droidective.core.net.proxy.FlowSink
import com.droidective.core.net.proxy.FlowStore
import com.droidective.core.net.proxy.Mock
import com.droidective.core.net.proxy.Outgoing
import com.droidective.core.net.proxy.ProxyRules
import com.droidective.core.net.proxy.ProxyServer
import com.droidective.core.net.proxy.ProxySetting
import com.droidective.core.net.proxy.Refusals
import com.droidective.core.net.proxy.Remap
import com.droidective.core.net.proxy.Repeat
import com.droidective.core.net.proxy.Repeater
import com.droidective.core.net.proxy.Throttle
import com.droidective.core.net.proxy.TrafficRules
import com.droidective.core.net.proxy.TrafficRulesRepository
import com.droidective.core.net.tls.ProxyCertificates
import com.droidective.core.net.ws.SocketLog
import com.droidective.core.net.ws.SocketMessage
import com.droidective.core.shell.ShellUnavailableException
import com.droidective.core.toggles.SettingScope
import com.droidective.platform.AndroidSettingsStore
import com.droidective.platform.SharedStorage
import java.io.File
import java.io.IOException
import java.net.BindException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Where the proxy is. */
sealed interface ProxyRun {
    data object Off : ProxyRun

    /** Making the certificate authority, the first time, which takes a second or two. */
    data object Starting : ProxyRun

    data class Listening(val port: Int) : ProxyRun

    data class Failed(val message: String) : ProxyRun
}

/** Whether the phone trusts the proxy's certificate, and in which store. */
enum class CertificateTrust {
    /** Not installed: apps refuse every HTTPS connection the proxy opens. */
    None,

    /** Installed by the person. Trusted by apps that opt in, such as a debug build. */
    User,

    /** In the system store, which only root can write. Trusted by every app that doesn't pin. */
    System,
}

/**
 * The one proxy, which outlives the screen that started it.
 *
 * It listens on the phone's own loopback address, so the phone is pointed at `127.0.0.1` and
 * nothing off the device can reach it — a proxy open to the network would be an open proxy for
 * everyone else on that Wi-Fi.
 */
object TrafficProxy {
    const val TOOL = "network-traffic"
    const val PORT = 8_888
    val ADDRESS = ProxySetting.here(PORT)

    private val current = MutableStateFlow<ProxyRun>(ProxyRun.Off)
    val state: StateFlow<ProxyRun> = current.asStateFlow()

    /** Bumped as exchanges arrive. The screen watches this and reads [flows] when it changes. */
    private val revision = MutableStateFlow(0L)
    val changes: StateFlow<Long> = revision.asStateFlow()

    val flows = FlowStore()

    /** What the WebSockets among [flows] said, by flow. */
    val messages = SocketLog()

    /** Flow ids for every run, so a run never reuses one the store still holds. */
    private val ids = AtomicLong()

    /**
     * The hosts apps have turned the certificate down for, which are passed through for them from
     * then on. Kept for one run: starting again, or the certificate's trust changing, starts
     * afresh.
     */
    val refusals = Refusals()

    private val pointed = MutableStateFlow(false)

    /**
     * Whether this phone's own proxy setting points here, as the setting says. Read back rather
     * than remembered: a process that Android ended and started again has no memory of having set
     * it.
     */
    val pointing: StateFlow<Boolean> = pointed.asStateFlow()

    private val chosen = MutableStateFlow(TrafficRules())
    @Volatile private var loaded = false

    /** The rules as chosen and kept. [loadRules] reads them the first time. */
    val rules: StateFlow<TrafficRules> = chosen.asStateFlow()

    private var server: ProxyServer? = null
    @Volatile private var authority: ProxyCertificates? = null

    val running: Boolean
        get() = current.value is ProxyRun.Listening

    /**
     * Starts listening and, when [point] is true and the phone allows it, points the phone here.
     */
    fun start(context: Context, point: Boolean) {
        if (running || current.value is ProxyRun.Starting) return
        current.value = ProxyRun.Starting
        try {
            ContextCompat.startForegroundService(
                context,
                service(context).putExtra(ProxyService.EXTRA_POINT, point),
            )
        } catch (e: IllegalStateException) {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException
            ) {
                current.value = ProxyRun.Failed("Android wouldn't start the proxy just now.")
            } else {
                throw e
            }
        }
    }

    /** Stops listening and puts the phone's proxy setting back the way it was. */
    fun stop(context: Context) {
        // At once, so a tile reading the state straight after its tap sees it stopped; putting the
        // setting back is the service's, while it is still in the foreground.
        shutDown()
        context.startService(service(context).setAction(ProxyService.ACTION_STOP))
    }

    fun clear() {
        flows.clear()
        messages.clear()
        revision.update { it + 1 }
    }

    /**
     * The authority, made and saved the first time it is asked for. Generating the keys is the slow
     * part, which is why this is worth doing before anyone presses anything.
     */
    suspend fun prepare(context: Context): ProxyCertificates? =
        withContext(Dispatchers.Default) {
            authority
                ?: try {
                    read(context).also { authority = it }
                } catch (_: IOException) {
                    null
                } catch (_: GeneralSecurityException) {
                    null
                }
        }

    /**
     * Saves the certificate to Downloads, where Android's own installer can pick it up.
     *
     * Since Android 11 an app cannot hand a CA certificate to the installer — the person has to
     * install it from Settings themselves — so this is the whole of what an app can do.
     *
     * @return the saved file, or null when there is no certificate yet or MediaStore refused it.
     * @throws IOException when the copy into Downloads fails.
     */
    suspend fun saveCertificate(context: Context): Uri? {
        val certificates = prepare(context) ?: return null
        val folder = SharedStorage.handover(context) ?: return null
        val file = File(folder, CERTIFICATE_FILE)
        withContext(Dispatchers.IO) { file.writeText(certificates.caPem) }
        return SharedStorage.publish(context, file, CERTIFICATE_MIME, SharedStorage.Place.Downloads)
    }

    /** Where the phone keeps the certificate, if it does. */
    suspend fun trust(context: Context): CertificateTrust {
        val ca = prepare(context)?.caCertificate ?: return CertificateTrust.None
        return withContext(Dispatchers.IO) {
            val alias =
                try {
                    KeyStore.getInstance(ANDROID_CA_STORE).run {
                        load(null)
                        getCertificateAlias(ca)
                    }
                } catch (_: GeneralSecurityException) {
                    null
                } catch (_: IOException) {
                    null
                }
            when {
                alias == null -> CertificateTrust.None
                alias.startsWith(SYSTEM_ALIAS) -> CertificateTrust.System
                else -> CertificateTrust.User
            }
        }
    }

    /** Reads the phone's proxy setting again, so [pointing] says what it holds now. */
    fun refreshPointing(context: Context): Boolean =
        ProxySetting.pointsHere(setting(context), PORT).also { pointed.value = it }

    /**
     * Points this phone's proxy setting here, or puts back whatever was there before.
     *
     * Taking it off leaves the setting alone when it no longer points here: somebody changed it
     * since, and what they chose is not this app's to undo.
     *
     * @return whether the setting now says what was asked.
     */
    suspend fun point(context: Context, here: Boolean): Boolean {
        val stored = setting(context)
        val pointsHere = ProxySetting.pointsHere(stored, PORT)
        if (pointsHere == here) return refreshPointing(context) == here
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value =
            if (here) {
                prefs.edit { putString(BEFORE, stored) }
                ProxySetting.here(PORT)
            } else {
                ProxySetting.restore(prefs.getString(BEFORE, null), PORT)
            }
        write(context, value)
        val now = refreshPointing(context)
        if (!here && !now) prefs.edit { remove(BEFORE) }
        return now == here
    }

    /**
     * Puts the phone's proxy setting back when it points here and the proxy is not running.
     *
     * A reboot, an update or a force stop ends the proxy without its service hearing of it, and a
     * phone left pointed at nothing has no network. The next time the app starts for any reason is
     * the next chance to undo that.
     *
     * @return whether the setting had to be, and was, put back.
     */
    suspend fun releaseIfStranded(context: Context): Boolean {
        val now = current.value
        if (now != ProxyRun.Off && now !is ProxyRun.Failed) return false
        if (!refreshPointing(context)) return false
        return point(context, here = false)
    }

    internal suspend fun run(context: Context) {
        current.value = ProxyRun.Starting
        refreshPointing(context)
        val certificates =
            prepare(context)
                ?: run {
                    current.value = ProxyRun.Failed("The certificate could not be made.")
                    return
                }
        try {
            refusals.clear()
            val running =
                ProxyServer(
                    certificates,
                    Recorder(context),
                    owners = ShellOwners(context),
                    refusals = refusals,
                    ids = ids,
                    breaker = Holds,
                    files = MockFolder(context),
                )
            loadRules(context)
            running.rules = proxyRules(context, chosen.value)
            loadMocks(context)
            running.mocks = mocks.value
            loadRemaps(context)
            running.remaps = remaps.value
            running.throttle = speed.value
            Holds.load(context)
            running.breakpoints = Holds.breakpoints.value
            val port = withContext(Dispatchers.IO) { running.start(PORT) }
            server = running
            current.value = ProxyRun.Listening(port)
        } catch (_: BindException) {
            current.value = ProxyRun.Failed("Another app is already listening on port $PORT.")
        } catch (e: IOException) {
            current.value = ProxyRun.Failed(e.message ?: "The proxy could not start listening.")
        }
    }

    /**
     * Sends [flow]'s request again and records the exchange, sent by this app, as a repeat of it.
     * It needs no running proxy: it goes to the server itself.
     *
     * @return the new flow, or null when [flow] has no request that can be sent again.
     */
    suspend fun repeat(context: Context, flow: Flow): Flow? {
        val ready = Repeater.prepare(flow) as? Repeat.Ready ?: return null
        return send(context, ready.outgoing, of = flow.id)
    }

    /**
     * Sends [outgoing] — a repeat of flow [of], an edited one, or one written in API Testing — and
     * records it as sent by this app. A remap sends it elsewhere as it would the app's own request,
     * so a repeat goes where the app's went.
     */
    suspend fun send(context: Context, outgoing: Outgoing, of: Long? = null): Flow {
        val sent =
            withContext(Dispatchers.IO) {
                loadRemaps(context)
                val via = Remap.route(remaps.value, outgoing.url)
                Repeater().send(outgoing, ids.incrementAndGet(), of, via)
            }
        val recorded = sent.copy(uid = Process.myUid(), app = context.packageName)
        flows.put(recorded)
        revision.update { it + 1 }
        return recorded
    }

    private val speed = MutableStateFlow(Throttle.NONE)

    /** How slow a network the proxy plays, for this session; it applies to the next request. */
    val throttle: StateFlow<Throttle> = speed.asStateFlow()

    fun throttle(slower: Throttle) {
        speed.value = slower
        server?.throttle = slower
    }

    /** Puts [points] in force on a running proxy at once. */
    internal fun applyBreakpoints(points: List<Breakpoint>) {
        server?.breakpoints = points
    }

    private val keptMocks =
        KeptList("traffic-mocks.json", Mock.serializer(), Mock::id) { server?.mocks = it }

    /** The answers given instead of the server's, in the order they were made. */
    val mocks: StateFlow<List<Mock>> = keptMocks.items

    /** Reads the kept mocks, once; after that [mocks] is the truth. */
    fun loadMocks(context: Context) = keptMocks.load(context)

    /** Keeps [mock], replacing the one with its id, and applies it at once. */
    fun saveMock(context: Context, mock: Mock) = keptMocks.save(context, mock)

    fun removeMock(context: Context, id: String) = keptMocks.remove(context, id)

    private val keptRemaps =
        KeptList("traffic-remaps.json", Remap.serializer(), Remap::id) { server?.remaps = it }

    /** Which requests go to another server, in the order they were made. */
    val remaps: StateFlow<List<Remap>> = keptRemaps.items

    fun loadRemaps(context: Context) = keptRemaps.load(context)

    /** Keeps [remap], replacing the one with its id, and applies it at once. */
    fun saveRemap(context: Context, remap: Remap) = keptRemaps.save(context, remap)

    fun removeRemap(context: Context, id: String) = keptRemaps.remove(context, id)

    /** Reads the kept rules, once; after that [rules] is the truth. */
    fun loadRules(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            chosen.value = repository(context).load()
            loaded = true
        }
    }

    /** Keeps [new] and, while the proxy runs, applies it to the next connection. */
    fun setRules(context: Context, new: TrafficRules) {
        loadRules(context)
        repository(context).save(new)
        chosen.value = new
        server?.rules = proxyRules(context, new)
    }

    /** The package behind a uid, as the flows name it. */
    fun appOf(context: Context, uid: Int): String? = AppNames.of(context, uid)

    private fun proxyRules(context: Context, rules: TrafficRules): ProxyRules =
        rules.toProxyRules { packageName ->
            try {
                context.packageManager.getPackageUid(packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }

    private fun repository(context: Context) = TrafficRulesRepository(context.filesDir)

    /** Stops listening. A failure stays on screen until the next start, so it can be read. */
    internal fun shutDown() {
        // Nobody is left to decide about what is held, and its connections are about to close.
        Holds.releaseAll()
        server?.stop()
        server = null
        if (current.value !is ProxyRun.Failed) current.value = ProxyRun.Off
    }

    private fun setting(context: Context): String? =
        Settings.Global.getString(context.contentResolver, ProxySetting.KEY)

    /**
     * Through the Settings API when the app holds WRITE_SECURE_SETTINGS, and the shell otherwise.
     */
    private suspend fun write(context: Context, value: String) {
        withContext(Dispatchers.IO) {
            val store = AndroidSettingsStore(context)
            if (
                store.canWrite(SettingScope.Global) &&
                    store.put(SettingScope.Global, ProxySetting.KEY, value)
            ) {
                return@withContext
            }
            try {
                context.droidective.access
                    .shell()
                    ?.run(SystemValues.put(ValueTable.Global, ProxySetting.KEY, value))
            } catch (_: ShellUnavailableException) {
                // Read back by the caller, which says whether it took.
            }
        }
    }

    private fun read(context: Context): ProxyCertificates {
        val file = File(context.filesDir, KEYSTORE)
        val saved = if (file.exists()) ProxyCertificates.load(file.readBytes(), PASSWORD) else null
        if (saved != null) return saved
        return ProxyCertificates.create(CERTIFICATE_NAME).also {
            file.writeBytes(it.save(PASSWORD))
        }
    }

    private fun service(context: Context) = Intent(context, ProxyService::class.java)

    /** Puts each exchange in the store with the app's name against it. */
    private class Recorder(private val context: Context) : FlowSink {
        override fun started(flow: Flow) = keep(flow)

        override fun finished(flow: Flow) = keep(flow)

        override fun message(flowId: Long, message: SocketMessage) {
            messages.put(flowId, message)
            revision.update { it + 1 }
        }

        override fun unread(flowId: Long, fromApp: Boolean, why: String) {
            messages.unread(flowId, fromApp, why)
            revision.update { it + 1 }
        }

        private fun keep(flow: Flow) {
            flows.put(flow.copy(app = flow.uid?.let { AppNames.of(context, it) }))
            revision.update { it + 1 }
        }
    }

    /**
     * Reads the kernel's socket table through the shell.
     *
     * The table is kept between connections and read again only when a port is not in it, which is
     * what happens for a connection that has just arrived. Connections arriving together — a
     * browser opens several at once — share one read rather than queueing a read each, and none of
     * this holds up a request: the proxy asks in the background.
     */
    private class ShellOwners(private val context: Context) : FlowOwners {
        @Volatile private var table = ""
        private var readAt = 0L

        override fun uidOf(port: Int): Int? {
            ConnectionOwners.owner(table, port)?.let {
                return it
            }
            synchronized(this) {
                // Whoever held the lock may have read a table that has this connection in it.
                ConnectionOwners.owner(table, port)?.let {
                    return it
                }
                val wait = readAt + LEAST_GAP - System.currentTimeMillis()
                if (wait > 0) Thread.sleep(wait)
                refresh()
                return ConnectionOwners.owner(table, port)
            }
        }

        private fun refresh() {
            readAt = System.currentTimeMillis()
            table =
                try {
                    runBlocking {
                        context.droidective.access
                            .shell()
                            ?.run(ConnectionOwners.read)
                            ?.takeIf { it.succeeded }
                            ?.stdout
                    }
                        .orEmpty()
                } catch (_: ShellUnavailableException) {
                    ""
                }
        }
    }

    /** An app's package name for a uid, asked of the system once and then remembered. */
    private object AppNames {
        private val known = ConcurrentHashMap<Int, String>()

        fun of(context: Context, uid: Int): String? =
            known
                .getOrPut(uid) {
                    context.packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()
                }
                .ifEmpty { null }
    }

    private const val CERTIFICATE_NAME = "Droidective Proxy"
    private const val CERTIFICATE_FILE = "droidective-proxy-ca.crt"
    private const val CERTIFICATE_MIME = "application/x-x509-ca-cert"
    private const val KEYSTORE = "proxy-ca.p12"
    private const val ANDROID_CA_STORE = "AndroidCAStore"
    private const val SYSTEM_ALIAS = "system:"
    private const val PREFS = "traffic-proxy"

    /** What the proxy setting held before the phone was pointed here, to put back afterwards. */
    private const val BEFORE = "before"

    /** How often the socket table may be read, however many connections arrive. */
    private const val LEAST_GAP = 100L

    /**
     * The keystore sits in the app's own storage, which is what keeps it private; the password is
     * what PKCS#12 requires rather than a secret of its own.
     */
    private val PASSWORD = "droidective".toCharArray()
}
