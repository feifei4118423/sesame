package com.utbionic.verysmartassistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class DeviceManager(
    private val scope: CoroutineScope,
    private val information: Information,
) {
    val PORT = 4211
    val TIMEOUT = 30000
    private var heartbeatJob: Job? = null

    private suspend fun sendRequest(command: String): Boolean {
        return withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(information.controllerAddress, PORT), TIMEOUT)
                    socket.soTimeout = TIMEOUT

                    val out: OutputStream = socket.getOutputStream()
                    out.write(command.toByteArray())
                    out.write("\n".toByteArray())
                    out.flush()

                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val response = reader.readLine()
                    val json = JSONObject(response)

                    json["success"] == true
                }
            }.getOrDefault(false)
        }
    }

    fun sendHeartbeat(
        callback: (Boolean) -> Unit
    ) {
        scope.launch {
            val success = sendRequest("HEARTBEAT")
            withContext(Dispatchers.Main) {
                callback(success)
            }
        }
    }

    fun startHeartbeat(
        interval: Long,
        callback: (Boolean) -> Unit = {},
    ) {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive) {
                val success = sendRequest("HEARTBEAT")
                withContext(Dispatchers.Main) {
                    callback(success)
                }
                delay(interval)
            }
        }
    }

    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun sendCommand(
        command: String,
        callback: (Boolean) -> Unit,
    ) {
        scope.launch {
            val success = sendRequest(command)
            withContext(Dispatchers.Main) {
                callback(success)
            }
        }
    }
}
