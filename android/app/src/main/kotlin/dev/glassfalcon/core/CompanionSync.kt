// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.glassfalcon.ui.screens.MapTelemetrySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

private const val SERVICE_TYPE = "_glassfalcon._tcp"
private const val SERVICE_NAME = "GlassFalcon"

/** Persists which "mode" this specific device/install should launch into, a companion phone
 *  used purely as a map-only HUD stays in that mode across restarts rather than needing to be
 *  re-selected every launch. Same SharedPreferences-backed pattern as ConsentStore. */
object CompanionModeStore {
    private const val PREFS = "companion_mode"
    private const val KEY_ENABLED = "companion_enabled"

    fun isCompanionMode(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setCompanionMode(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}

/**
 * "Map-only HUD" companion mode (task: a 2nd phone/tablet showing just live drone position,
 * no flight controls, no DUML link of its own), a spotter/passenger device connects to the
 * PRIMARY phone (the one actually flying) over whatever IP network the two share: home/hotspot
 * LAN, USB tether, or Wi-Fi Direct/ad-hoc all present as ordinary network interfaces to
 * Android, so one plain TCP server + NSD (mDNS) advertisement covers all three. Bluetooth is
 * NOT covered here, it's a genuinely different transport (RFCOMM/GATT, no IP stack), out of
 * scope for this version.
 *
 * [CompanionServer] runs on the primary phone and broadcasts telemetry; [CompanionClient] runs
 * on the companion and implements [MapTelemetrySource] itself, so the companion's map screen is
 * the literal same [dev.glassfalcon.ui.screens.FlightMap] composable the primary phone uses,
 * just fed over the network instead of a live DUML link.
 */
class CompanionServer(private val context: Context, private val source: MapTelemetrySource) {
    private var serverSocket: ServerSocket? = null
    private var job: Job? = null
    private val clients = mutableListOf<Socket>()
    private val nsdManager by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
    private var registrationListener: NsdManager.RegistrationListener? = null

    private val _clientCount = MutableStateFlow(0)
    val clientCount: StateFlow<Int> = _clientCount
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            val socket = try { ServerSocket(0) } catch (e: Exception) { return@launch }
            serverSocket = socket
            _running.value = true
            registerNsd(socket.localPort)
            launch { acceptLoop(socket) }
            broadcastLoop()
        }
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (currentCoroutineContext().isActive) {
            val client = try { socket.accept() } catch (e: Exception) { break }
            synchronized(clients) { clients += client }
            _clientCount.value = clients.size
        }
    }

    // 2Hz position ticks (cheap, always sent); the track list is only re-sent when it actually
    // grows, FlightViewModel already gates new track points behind a 1m-moved check, so this
    // isn't re-sending a multi-thousand-point list every 500ms in practice.
    private suspend fun broadcastLoop() {
        var lastTrackSize = -1
        while (currentCoroutineContext().isActive) {
            val d = source.drone.value
            val home = source.homePoint.value
            val lastKnown = source.lastKnown.value
            val pos = JSONObject().apply {
                put("type", "pos")
                put("lat", d.lat); put("lon", d.lon)
                put("vx", d.vx); put("vy", d.vy); put("vz", d.vz)
                put("home", home?.let { JSONArray().put(it.first).put(it.second) })
                put("lastKnown", lastKnown?.let { JSONArray().put(it.first).put(it.second) })
            }
            send(pos.toString() + "\n")

            val track = source.track.value
            if (track.size != lastTrackSize) {
                lastTrackSize = track.size
                val pts = JSONArray()
                track.forEach { p -> pts.put(JSONArray().put(p.lat).put(p.lon).put(p.speed).put(p.alt)) }
                send(JSONObject().apply { put("type", "track"); put("points", pts) }.toString() + "\n")
            }
            delay(500)
        }
    }

    private fun send(line: String) {
        val bytes = line.toByteArray()
        synchronized(clients) {
            val dead = mutableListOf<Socket>()
            clients.forEach { c ->
                try { c.getOutputStream().write(bytes); c.getOutputStream().flush() }
                catch (e: Exception) { dead += c }
            }
            clients.removeAll(dead)
            _clientCount.value = clients.size
        }
    }

    private fun registerNsd(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        registrationListener = listener
        runCatching { nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun stop() {
        job?.cancel(); job = null
        registrationListener?.let { runCatching { nsdManager.unregisterService(it) } }
        registrationListener = null
        synchronized(clients) { clients.forEach { runCatching { it.close() } }; clients.clear() }
        runCatching { serverSocket?.close() }
        serverSocket = null
        _clientCount.value = 0
        _running.value = false
    }
}

/** Runs on the companion device: discovers a [CompanionServer] via NSD, connects, and exposes
 *  the incoming telemetry through [MapTelemetrySource], the same interface [FlightViewModel]
 *  implements for its own live DUML-fed map. */
class CompanionClient(private val context: Context) : MapTelemetrySource {
    private val _drone = MutableStateFlow(DroneState())
    override val drone: StateFlow<DroneState> = _drone
    private val _track = MutableStateFlow<List<TrackPoint>>(emptyList())
    override val track: StateFlow<List<TrackPoint>> = _track
    private val _homePoint = MutableStateFlow<Pair<Double, Double>?>(null)
    override val homePoint: StateFlow<Pair<Double, Double>?> = _homePoint
    private val _lastKnown = MutableStateFlow<Pair<Double, Double>?>(null)
    override val lastKnown: StateFlow<Pair<Double, Double>?> = _lastKnown

    data class Found(val name: String, val host: String, val port: Int)
    private val _discovered = MutableStateFlow<List<Found>>(emptyList())
    val discovered: StateFlow<List<Found>> = _discovered
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val nsdManager by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var connectJob: Job? = null

    fun startDiscovery() {
        if (discoveryListener != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType != SERVICE_TYPE) return
                nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        val found = Found(info.serviceName, host, info.port)
                        _discovered.value = _discovered.value.filterNot { it.name == found.name } + found
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                _discovered.value = _discovered.value.filterNot { it.name == info.serviceName }
            }
        }
        discoveryListener = listener
        runCatching { nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun stopDiscovery() {
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        discoveryListener = null
    }

    fun connect(scope: CoroutineScope, host: String, port: Int) {
        disconnect()
        connectJob = scope.launch(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 5000)
                    _connected.value = true
                    socket.getInputStream().bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (!currentCoroutineContext().isActive) break
                            runCatching { handle(JSONObject(line)) }
                        }
                    }
                }
            } catch (e: Exception) {
                // Best-effort, a dropped companion link shouldn't crash anything, the pilot's
                // own flight controls are on the OTHER phone and unaffected either way.
            } finally {
                _connected.value = false
            }
        }
    }

    private fun handle(obj: JSONObject) {
        when (obj.optString("type")) {
            "pos" -> {
                _drone.value = DroneState(
                    lat = obj.optDouble("lat"), lon = obj.optDouble("lon"),
                    vx = obj.optDouble("vx").toFloat(), vy = obj.optDouble("vy").toFloat(), vz = obj.optDouble("vz").toFloat(),
                )
                obj.optJSONArray("home")?.let { _homePoint.value = it.getDouble(0) to it.getDouble(1) }
                obj.optJSONArray("lastKnown")?.let { _lastKnown.value = it.getDouble(0) to it.getDouble(1) }
            }
            "track" -> {
                val pts = obj.optJSONArray("points") ?: return
                _track.value = (0 until pts.length()).map { i ->
                    val a = pts.getJSONArray(i)
                    TrackPoint(a.getDouble(0), a.getDouble(1), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())
                }
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel(); connectJob = null
        _connected.value = false
    }
}
