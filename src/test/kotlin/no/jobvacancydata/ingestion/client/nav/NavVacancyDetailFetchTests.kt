package no.jobvacancydata.ingestion.client.nav

import java.io.IOException
import java.time.Duration
import no.jobvacancydata.ingestion.application.nav.VacancyDetailFetchResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper

class NavVacancyDetailFetchTests {
	private val jsonMapper = JsonMapper.builder().build()

	@Test
	fun `fetches and maps vacancy detail`() {
		val builder = RestClient.builder()
		val server = MockRestServiceServer.bindTo(builder).build()
		val client = createClient(builder.build())

		server.expect(requestTo(DETAIL_URL))
			.andRespond(withSuccess(detailJson(ENTRY_ID), MediaType.APPLICATION_JSON))

		val result = client.fetchVacancyDetail(DETAIL_URL) as VacancyDetailFetchResult.Fetched

		assertThat(result.detail.uuid).isEqualTo(ENTRY_ID)
		assertThat(result.detail.status).isEqualTo("ACTIVE")
		assertThat(result.detail.adContent?.employer?.name).isEqualTo("Synthetic employer")
		server.verify()
	}

	@Test
	fun `maps inactive entry with null ad content`() {
		val builder = RestClient.builder()
		val server = MockRestServiceServer.bindTo(builder).build()
		val client = createClient(builder.build())

		server.expect(requestTo(DETAIL_URL))
			.andRespond(withSuccess(inactiveDetailJson(), MediaType.APPLICATION_JSON))

		val result = client.fetchVacancyDetail(DETAIL_URL) as VacancyDetailFetchResult.Fetched

		assertThat(result.detail.status).isEqualTo("INACTIVE")
		assertThat(result.detail.adContent).isNull()
		server.verify()
	}

	@Test
	fun `returns not found for documented 404`() {
		val builder = RestClient.builder()
		val server = MockRestServiceServer.bindTo(builder).build()
		val client = createClient(builder.build())

		server.expect(requestTo(DETAIL_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND))

		assertThat(client.fetchVacancyDetail(DETAIL_URL)).isEqualTo(VacancyDetailFetchResult.NotFound)
		server.verify()
	}

	@Test
	fun `rejects identity mismatch between requested entry and response`() {
		val builder = RestClient.builder()
		val server = MockRestServiceServer.bindTo(builder).build()
		val client = createClient(builder.build())

		server.expect(requestTo(DETAIL_URL))
			.andRespond(withSuccess(detailJson(OTHER_ENTRY_ID), MediaType.APPLICATION_JSON))

		assertThrows<NavFeedIdentityMismatchException> {
			client.fetchVacancyDetail(DETAIL_URL)
		}
		server.verify()
	}

	@Test
	fun `retries transport and server failures`() {
		val builder = RestClient.builder()
		val server = MockRestServiceServer.bindTo(builder).build()
		val client = createClient(builder.build())

		server.expect(requestTo(DETAIL_URL))
			.andRespond(withException(IOException("synthetic I/O failure")))
		server.expect(requestTo(DETAIL_URL))
			.andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))
		server.expect(requestTo(DETAIL_URL))
			.andRespond(withSuccess(detailJson(ENTRY_ID), MediaType.APPLICATION_JSON))

		val result = client.fetchVacancyDetail(DETAIL_URL)

		assertThat(result).isInstanceOf(VacancyDetailFetchResult.Fetched::class.java)
		server.verify()
	}

	@Test
	fun `stops retrying after configured attempts`() {
		val builder = RestClient.builder()
		val server = MockRestServiceServer.bindTo(builder).build()
		val client = createClient(builder.build())

		repeat(RETRY_TEST_ATTEMPTS) {
			server.expect(requestTo(DETAIL_URL))
				.andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))
		}

		assertThrows<NavFeedRetryableException> {
			client.fetchVacancyDetail(DETAIL_URL)
		}
		server.verify()
	}

	@Test
	fun `maps unauthorized responses distinctly and does not retry`() {
		listOf(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN).forEach { status ->
			val builder = RestClient.builder()
			val server = MockRestServiceServer.bindTo(builder).build()
			val client = createClient(builder.build())

			server.expect(requestTo(DETAIL_URL)).andRespond(withStatus(status))

			assertThrows<NavFeedUnauthorizedException> {
				client.fetchVacancyDetail(DETAIL_URL)
			}
			server.verify()
		}
	}

	@Test
	fun `exposes retry-after for rate limited response without retrying`() {
		val builder = RestClient.builder()
		val server = MockRestServiceServer.bindTo(builder).build()
		val client = createClient(builder.build())

		server.expect(requestTo(DETAIL_URL))
			.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "5"))

		val exception = assertThrows<NavFeedRateLimitedException> {
			client.fetchVacancyDetail(DETAIL_URL)
		}

		assertThat(exception.retryAfter).isEqualTo("5")
		server.verify()
	}

	@Test
	fun `does not retry mapping failures`() {
		val builder = RestClient.builder()
		val server = MockRestServiceServer.bindTo(builder).build()
		val client = createClient(builder.build())
		val responseMarker = "synthetic-malformed-detail-marker"

		server.expect(requestTo(DETAIL_URL))
			.andRespond(withSuccess(responseMarker, MediaType.APPLICATION_JSON))

		val exception = assertThrows<NavFeedMalformedResponseException> {
			client.fetchVacancyDetail(DETAIL_URL)
		}

		assertThat(exception.message).doesNotContain(responseMarker)
		server.verify()
	}

	@Test
	fun `rejects cross-origin detail url`() {
		val client = createClient(RestClient.builder().build())

		val exception = assertThrows<IllegalArgumentException> {
			client.fetchVacancyDetail("https://evil.test${NavFeedPaths.FEED_ENTRY_PATH}$ENTRY_ID")
		}

		assertThat(exception.message).contains("origin")
	}

	@Test
	fun `rejects detail url outside feed entry path`() {
		val client = createClient(RestClient.builder().build())

		val exception = assertThrows<IllegalArgumentException> {
			client.fetchVacancyDetail("$TEST_BASE_URL${NavFeedPaths.FEED_PATH}/$ENTRY_ID")
		}

		assertThat(exception.message).contains(NavFeedPaths.FEED_ENTRY_PATH)
	}

	@Test
	fun `rejects detail url without a single entry id`() {
		val client = createClient(RestClient.builder().build())

		assertThrows<IllegalArgumentException> {
			client.fetchVacancyDetail("$TEST_BASE_URL${NavFeedPaths.FEED_ENTRY_PATH}")
		}
		assertThrows<IllegalArgumentException> {
			client.fetchVacancyDetail("$TEST_BASE_URL${NavFeedPaths.FEED_ENTRY_PATH}$ENTRY_ID/extra")
		}
	}

	private fun createClient(restClient: RestClient): NavFeedRestClient =
		NavFeedRestClient(
			restClient,
			NavFeedPageMapper(jsonMapper),
			NavVacancyDetailResponseMapper(jsonMapper),
			TEST_BASE_URL,
			RETRY_TEST_ATTEMPTS,
			Duration.ZERO,
		)

	private fun detailJson(entryId: String): String =
		"""
		{
		  "uuid": "$entryId",
		  "sistEndret": "2026-01-02T03:04:05Z",
		  "status": "ACTIVE",
		  "ad_content": {
		    "uuid": "synthetic-ad-id",
		    "published": "2026-01-01T00:00:00Z",
		    "expires": "2026-02-01T00:00:00Z",
		    "updated": "2026-01-02T00:00:00Z",
		    "workLocations": [],
		    "title": "Synthetic role",
		    "occupationCategories": [],
		    "categoryList": [],
		    "link": "https://example.test/vacancy",
		    "employer": {"name": "Synthetic employer", "orgnr": "000000000"}
		  }
		}
		""".trimIndent()

	private fun inactiveDetailJson(): String =
		"""
		{
		  "uuid": "$ENTRY_ID",
		  "sistEndret": "2026-01-02T03:04:05Z",
		  "status": "INACTIVE",
		  "ad_content": null
		}
		""".trimIndent()

	private companion object {
		const val RETRY_TEST_ATTEMPTS = 3
		const val TEST_BASE_URL = "https://pam-stilling-feed.test"
		const val ENTRY_ID = "550e8400-e29b-41d4-a716-446655440000"
		const val OTHER_ENTRY_ID = "550e8400-e29b-41d4-a716-446655440001"
		const val DETAIL_URL = "$TEST_BASE_URL${NavFeedPaths.FEED_ENTRY_PATH}$ENTRY_ID"
	}
}
