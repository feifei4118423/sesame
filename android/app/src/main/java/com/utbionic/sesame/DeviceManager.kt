package com.utbionic.sesame

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

class DeviceManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val PORT = 4211
        private const val SOCKET_TIMEOUT_MS = 3_000
        private const val DISCOVERY_TIMEOUT_MS = 15_000L
        private const val HEARTBEAT_INTERVAL_MS = 10_000L
        private const val RECONNECT_DELAY_MS = 10_000L
        private const val SERVICE_TYPE = "_sesame._tcp."

        private const val SHARED_SECRET = "sesame-8Kq2mVx7"
    }

    private var lifecycleJob: Job? = null

    @Volatile
    private var activeAddress: String? = null

    fun start(onConnectionChanged: (Boolean) -> Unit) {
        stop()
        lifecycleJob = scope.launch {
            var connected = false
            while (isActive) {
                if (!connected) {
                    val ip = discoverControllerIpAddress()
                    if (ip == null) {
                        notify(onConnectionChanged, false)
                        delay(RECONNECT_DELAY_MS.milliseconds)
                        continue
                    }
                    activeAddress = ip
                    connected = sendRequest("CONNECT")
                    notify(onConnectionChanged, connected)
                    if (!connected) {
                        delay(RECONNECT_DELAY_MS.milliseconds)
                        continue
                    }
                }

                delay(HEARTBEAT_INTERVAL_MS.milliseconds)
                connected = sendRequest("HEARTBEAT")
                notify(onConnectionChanged, connected)
            }
        }
    }

    fun stop() {
        lifecycleJob?.cancel()
        lifecycleJob = null
    }

    fun sendCommand(command: String, callback: (Boolean) -> Unit) {
        scope.launch {
            val success = sendRequest(command)
            withContext(Dispatchers.Main) { callback(success) }
        }
    }

    private suspend fun notify(callback: (Boolean) -> Unit, value: Boolean) {
        withContext(Dispatchers.Main) { callback(value) }
    }

    private suspend fun sendRequest(command: String): Boolean = withContext(Dispatchers.IO) {
        val target = activeAddress ?: return@withContext false
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(target, PORT), SOCKET_TIMEOUT_MS)
                socket.soTimeout = SOCKET_TIMEOUT_MS

                val out = socket.getOutputStream()
                out.write(("$SHARED_SECRET $command\n").toByteArray())
                out.flush()

                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val response = reader.readLine()
                JSONObject(response)["success"] == true
            }
        }.getOrDefault(false)
    }

    private suspend fun discoverControllerIpAddress(): String? {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val deferredIp = CompletableDeferred<String?>()

        val resolving = AtomicBoolean(false)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(service: NsdServiceInfo) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.contains("_sesame._tcp") && !deferredIp.isCompleted &&
                    resolving.compareAndSet(false, true)
                ) {
                    @Suppress("DEPRECATION") nsdManager.resolveService(
                        service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                resolving.set(false)
                            }

                            override fun onServiceResolved(info: NsdServiceInfo) {
                                @Suppress("DEPRECATION") val hostIp = info.host?.hostAddress
                                if (hostIp != null && !deferredIp.isCompleted) {
                                    deferredIp.complete(hostIp)
                                }
                                resolving.set(false)
                            }
                        })
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (!deferredIp.isCompleted) deferredIp.complete(null)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        return withContext(Dispatchers.IO) {
            val started = runCatching {
                nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            }.isSuccess
            if (!started) return@withContext null

            val ip = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS.milliseconds) { deferredIp.await() }
            runCatching { nsdManager.stopServiceDiscovery(listener) }
            ip
        }
    }
}