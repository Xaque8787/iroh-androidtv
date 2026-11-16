package org.jellyfin.androidtv.iroh

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import io.github.xaque8787.kausway.KauswayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Process-owned endpoint. Only this non-routable address is stored in the Jellyfin account store. */
class IrohTransport(private val context: Context) {
	companion object {
		const val ADDRESS = "http://iroh.invalid/"
		fun isInvite(address: String) = address.trim().startsWith("kausway://invite/", ignoreCase = true)
	}

	val hasSavedPairing: Boolean get() = context.noBackupFilesDir.resolve("kausway-client/host.json").isFile

	private val mutex = Mutex()
	private var client: KauswayClient? = null
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	private suspend fun client(): KauswayClient {
		client?.let { return it }
		val initialized = KauswayClient.initialize(context.applicationContext)
		client = initialized
		val connectivity = context.getSystemService(ConnectivityManager::class.java)
		connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
			override fun onAvailable(network: Network) = changed()
			override fun onLost(network: Network) = changed()
			private fun changed() { scope.launch { runCatching { initialized.notifyNetworkChange() } } }
		})
		return initialized
	}

	suspend fun pair(invite: String) = mutex.withLock { client().pair(invite.trim()) }
	suspend fun forget() = mutex.withLock { client().forgetPairing() }
	private suspend fun proxy(): HttpUrl = mutex.withLock { client().startProxy().proxyUrl.toHttpUrl() }

	private val routing = IrohHttpRouting { runBlocking(Dispatchers.IO) { proxy() } }
	fun interceptor(media: Boolean = false, active: () -> Boolean = { false }) = routing.interceptor(media, active)
	fun redirectInterceptor() = routing.redirectInterceptor()
}
