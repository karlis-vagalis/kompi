package com.kompi.app.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable

/** Default-network availability transitions for the foreground selected-host transport. */
data class NetworkAvailability(
    val isOnline: Boolean,
    /** Monotonic process-local generation; changes only for a meaningful default-network transition. */
    val generation: Long,
    val networkHandle: Long?,
)

/**
 * Observes Android's default network without owning or changing the remote daemon/session.
 * Close this monitor with its transport owner to unregister the platform callback.
 */
class NetworkAvailabilityMonitor(context: Context) : Closeable {
    private val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val lock = Any()
    private var defaultNetwork: Network? = connectivity.activeNetwork
    private var closed = false
    private val _state = MutableStateFlow(NetworkAvailability(defaultNetwork != null, 0L, defaultNetwork?.networkHandle))
    val state: StateFlow<NetworkAvailability> = _state.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(lock) {
                if (closed || defaultNetwork == network) return
                val previous = defaultNetwork
                defaultNetwork = network
                publish(isOnline = true, network = network, callback = "available", previous = previous)
            }
        }

        override fun onLost(network: Network) {
            synchronized(lock) {
                if (closed || defaultNetwork != network) return
                defaultNetwork = null
                publish(isOnline = false, network = null, callback = "lost", previous = network)
            }
        }

        override fun onUnavailable() {
            synchronized(lock) {
                if (closed || defaultNetwork == null) return
                defaultNetwork = null
                publish(isOnline = false, network = null, callback = "unavailable", previous = null)
            }
        }
    }

    init {
        connectivity.registerDefaultNetworkCallback(callback)
    }

    private fun publish(isOnline: Boolean, network: Network?, callback: String, previous: Network?) {
        val current = _state.value
        val next = NetworkAvailability(isOnline, Math.addExact(current.generation, 1L), network?.networkHandle)
        _state.value = next
        Log.i("KompiSync", "phase=network_transition elapsed_ms=${SystemClock.elapsedRealtime()} callback=$callback generation=${next.generation} online=$isOnline from=${previous?.networkHandle ?: "none"} to=${next.networkHandle ?: "none"}")
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        connectivity.unregisterNetworkCallback(callback)
    }
}
