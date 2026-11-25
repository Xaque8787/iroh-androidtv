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
		private const val MAX_CODE_INPUT_LENGTH = 64
		private val compactShortCodeCandidate = Regex("K1[0-9A-Z]*", RegexOption.IGNORE_CASE)

		fun isPairingInput(address: String): Boolean {
			val input = address.trim()
			if (input.startsWith("kausway://invite/", ignoreCase = true)) return true
			// Route incomplete/mistyped formatted codes to SDK validation, not HTTP discovery
			// where the input could appear in server URLs, error text or logs.
			if (input.startsWith("K1-", ignoreCase = true) || input.startsWith("K1 ", ignoreCase = true)) return true
			if (input.length > MAX_CODE_INPUT_LENGTH) return false
			// Recognize compact, spaced and lowercase forms too. Only classify here:
			// the SDK validates/normalizes the original input and resolves the invitation.
			return compactShortCodeCandidate.matches(input.replace(" ", "").replace("-", ""))
		}
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
