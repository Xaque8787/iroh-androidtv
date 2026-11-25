package org.jellyfin.androidtv.iroh

import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress

class IrohHttpRoutingTests : FunSpec({
	test("API headers, encoded paths, redirects, HLS and Range stay inside the capability") {
		val requests = mutableListOf<String>()
		val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.createContext("/") { exchange ->
			requests += exchange.requestURI.toString()
			exchange.requestHeaders.getFirst("Authorization") shouldBe "MediaBrowser test"
			val path = exchange.requestURI.path
			when {
				path.endsWith("relative-redirect") -> {
					exchange.responseHeaders.add("Location", "../root-redirect")
					exchange.sendResponseHeaders(302, -1)
				}
				path.endsWith("root-redirect") -> {
					exchange.responseHeaders.add("Location", "/redirect")
					exchange.sendResponseHeaders(302, -1)
				}
				path.endsWith("redirect") -> {
					exchange.responseHeaders.add("Location", "http://unreachable.lan/Videos/a%20b/master.m3u8?token=abc")
					exchange.sendResponseHeaders(302, -1)
				}
				path.endsWith(".m3u8") -> {
					val body = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"http://unreachable.lan/key\"\n/segment.ts\nrelative.ts\n".toByteArray()
					exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
					exchange.sendResponseHeaders(200, body.size.toLong())
					exchange.responseBody.write(body)
				}
				else -> {
					exchange.requestHeaders.getFirst("Range") shouldBe "bytes=2-5"
					exchange.responseHeaders.add("Content-Range", "bytes 2-5/10")
					exchange.sendResponseHeaders(206, 4)
					exchange.responseBody.write("2345".toByteArray())
				}
			}
			exchange.close()
		}
		server.start()
		try {
			val routing = IrohHttpRouting { "http://127.0.0.1:${server.address.port}/secret/".toHttpUrl() }
			val client = OkHttpClient.Builder().addInterceptor(routing.interceptor())
				.addNetworkInterceptor(routing.redirectInterceptor()).build()
			client.newCall(Request.Builder().url("http://iroh.invalid/folder/relative-redirect")
				.header("Authorization", "MediaBrowser test").build()).execute().use {
				it.code shouldBe 200
				it.request.url.toString() shouldBe "http://iroh.invalid/Videos/a%20b/master.m3u8?token=abc"
				it.body!!.string() shouldBe "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"http://iroh.invalid/key\"\n/segment.ts\nrelative.ts\n"
			}
			client.newCall(Request.Builder().url("http://iroh.invalid/segment.ts").header("Authorization", "MediaBrowser test")
				.header("Range", "bytes=2-5").build()).execute().use {
				it.code shouldBe 206
				it.header("Content-Range") shouldBe "bytes 2-5/10"
				it.body!!.string() shouldBe "2345"
			}
			requests shouldBe listOf("/secret/folder/relative-redirect", "/secret/root-redirect", "/secret/redirect", "/secret/Videos/a%20b/master.m3u8?token=abc", "/secret/segment.ts")
		} finally { server.stop(0) }
	}
	test("ordinary HTTP bypasses Iroh and absolute media URLs use the current endpoint") {
		val requests = mutableListOf<String>()
		val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.createContext("/") { exchange ->
			requests += exchange.requestURI.toString()
			exchange.sendResponseHeaders(200, 2)
			exchange.responseBody.use { it.write("ok".toByteArray()) }
		}
		server.start()
		try {
			var capability = "first"
			var starts = 0
			val routing = IrohHttpRouting {
				starts++
				"http://127.0.0.1:${server.address.port}/$capability/".toHttpUrl()
			}
			val regular = OkHttpClient.Builder().addInterceptor(routing.interceptor())
				.addNetworkInterceptor(routing.redirectInterceptor()).build()
			regular.newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}/direct").build()).execute().close()
			starts shouldBe 0
			val media = regular.newBuilder().apply { interceptors().clear() }
				.addInterceptor(routing.interceptor(media = true) { true }).build()
			media.newCall(Request.Builder().url("https://lan.invalid/Videos/stream?static=true").build()).execute().use {
				it.request.url.toString() shouldBe "http://iroh.invalid/Videos/stream?static=true"
				it.body!!.string() shouldBe "ok"
			}
			capability = "after-restart"
			regular.newCall(Request.Builder().url("http://iroh.invalid/image").build()).execute().close()
			requests shouldBe listOf("/direct", "/first/Videos/stream?static=true", "/after-restart/image")
		} finally { server.stop(0) }
	}

})
