package org.jellyfin.androidtv.iroh

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

/** Keeps Jellyfin URLs stable while forwarding HTTP, media and WebSocket handshakes over Iroh. */
internal class IrohHttpRouting(private val proxy: () -> HttpUrl) {
	private class RoutedRequest(val base: HttpUrl)

	/** Map origin-root paths underneath the private capability; preserve encoded paths and queries. */
	fun interceptor(media: Boolean = false, active: () -> Boolean = { false }) = Interceptor { chain ->
		val original = chain.request()
		if (original.url.host != "iroh.invalid" && !(media && active())) {
			chain.proceed(original)
		} else {
			val base = try {
				proxy()
			} catch (_: Exception) {
				throw IOException("Iroh connection unavailable; check connectivity or pair again")
			}
			val target = base.newBuilder()
				.encodedPath(base.encodedPath + original.url.encodedPath.removePrefix("/"))
				.encodedQuery(original.url.encodedQuery).build()
			val response = chain.proceed(original.newBuilder().url(target).tag(RoutedRequest::class.java, RoutedRequest(base)).removeHeader("Host").build())
			val finalUrl = logical(response.request.url, base)
			val result = response.newBuilder().request(original.newBuilder().url(finalUrl).build())
			// Resolve redirects against the logical origin, never the ephemeral capability URL.
			response.header("Location")?.let { location ->
				finalUrl.resolve(location)?.let { result.header("Location", logical(it, base).toString()) }
			}
			// HLS manifests may contain absolute server URLs or root-relative segment/key/subtitle URLs.
			// Relative entries already resolve against the logical origin in Media3.
			val body = response.body
			if (body != null && isHls(response, finalUrl)) {
				val source = body.source()
				if (source.request(2L * 1024 * 1024 + 1)) {
					response.close()
					throw IOException("Iroh HLS manifest exceeds 2 MiB")
				}
				val text = body.string().replace(Regex("https?://[^\\s\\\"<>]+")) { match ->
					runCatching { logical(match.value.toHttpUrl(), base).toString() }.getOrDefault(match.value)
				}
				result.removeHeader("Content-Length").removeHeader("Content-Encoding")
					.body(text.toResponseBody(body.contentType()))
			}
			result.build()
		}
	}

	/** Runs before OkHttp follows a redirect, retaining the capability for root/absolute locations. */
	fun redirectInterceptor() = Interceptor { chain ->
		val request = chain.request()
		val response = chain.proceed(request)
		val routed = request.tag(RoutedRequest::class.java)
		if (routed == null) response else {
			val base = routed.base
			val location = response.header("Location")
			if (request.url.port != base.port || !request.url.encodedPath.startsWith(base.encodedPath) || location == null) response
			else {
				val resolved = request.url.resolve(location)
				if (resolved == null) response else {
					val path = if (resolved.host == base.host && resolved.port == base.port && resolved.encodedPath.startsWith(base.encodedPath))
						resolved.encodedPath.removePrefix(base.encodedPath) else resolved.encodedPath.removePrefix("/")
					val target = base.newBuilder().encodedPath(base.encodedPath + path).encodedQuery(resolved.encodedQuery).build()
					response.newBuilder().header("Location", target.toString()).build()
				}
			}
		}
	}

	private fun logical(url: HttpUrl, base: HttpUrl): HttpUrl {
		val path = if (url.host == base.host && url.port == base.port && url.encodedPath.startsWith(base.encodedPath))
			"/" + url.encodedPath.removePrefix(base.encodedPath) else url.encodedPath
		return url.newBuilder().scheme("http").host("iroh.invalid").port(80).encodedPath(path).build()
	}
	private fun isHls(response: Response, url: HttpUrl): Boolean =
		url.encodedPath.endsWith(".m3u8", ignoreCase = true) ||
			response.header("Content-Type").orEmpty().contains("mpegurl", ignoreCase = true)
}
