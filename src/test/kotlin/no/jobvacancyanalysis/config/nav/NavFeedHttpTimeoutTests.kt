package no.jobvacancyanalysis.config.nav

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import no.jobvacancyanalysis.ingestion.client.nav.NavFeedPageMapper
import no.jobvacancyanalysis.ingestion.client.nav.NavFeedPaths
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.client.ResourceAccessException
import tools.jackson.databind.json.JsonMapper

class NavFeedHttpTimeoutTests {
	@Test
	fun `applies configured response timeout to NAV requests`() {
		val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.createContext(NavFeedPaths.FEED_PATH) { exchange ->
			try {
				Thread.sleep(500)
				val body = """
					{
					  "version": "1",
					  "title": "Synthetic feed",
					  "home_page_url": "https://example.test",
					  "feed_url": "/api/v1/feed",
					  "description": "Synthetic feed description",
					  "id": "550e8400-e29b-41d4-a716-446655440000",
					  "items": []
					}
				""".trimIndent().toByteArray()
				exchange.sendResponseHeaders(200, body.size.toLong())
				exchange.responseBody.use { it.write(body) }
			} catch (_: Exception) {
				exchange.close()
			}
		}
		server.start()

		try {
			val properties = NavFeedProperties(
				baseUrl = "http://127.0.0.1:${server.address.port}",
				token = "synthetic-token",
				connectTimeout = Duration.ofSeconds(1),
				readTimeout = Duration.ofMillis(100),
				retryMaxAttempts = 1,
				retryWaitDuration = Duration.ZERO,
			)
			val client = NavFeedConfig().navFeedClient(
				NavFeedPageMapper(JsonMapper.builder().build()),
				properties,
			)

			assertThrows<ResourceAccessException> {
				client.fetchPage()
			}
		} finally {
			server.stop(0)
		}
	}
}
