package no.jobvacancydata.ingestion.application.nav

import java.time.OffsetDateTime
import no.jobvacancydata.ingestion.client.nav.NavFeedClient
import no.jobvacancydata.ingestion.client.nav.NavFeedInvalidDetailUrlException
import no.jobvacancydata.ingestion.client.nav.NavFeedMappingException
import no.jobvacancydata.ingestion.client.nav.NavFeedRateLimitedException
import no.jobvacancydata.ingestion.client.nav.NavFeedRetryableException
import no.jobvacancydata.ingestion.client.nav.NavFeedUnauthorizedException
import no.jobvacancydata.ingestion.client.validation.MappingError
import no.jobvacancydata.ingestion.client.validation.MappingErrorReport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NavCollectionWorkerTests {

	@Test
	fun `collects every item detail to the feed tail`() {
		val first = feedItem("synthetic-id-1")
		val second = feedItem("synthetic-id-2")
		val client = StubNavFeedClient(
			pages = listOf(
				{ pageResult(PAGE_ONE_URL, PAGE_TWO_URL, listOf(first)) },
				{ pageResult(PAGE_TWO_URL, null, listOf(second)) },
			),
			details = mapOf(
				first.url to { fetched(first.id) },
				second.url to { fetched(second.id) },
			),
		)

		val outcome = NavCollectionWorker(client).collect()

		assertThat(outcome.completion).isEqualTo(NavCollectionCompletion.ReachedTail)
		assertThat(outcome.resumeCursor).isEqualTo(FeedPageCursor(PAGE_TWO_URL))
		assertThat(outcome.items.map { it.feedItem }).containsExactly(first, second)
		assertThat(outcome.items).allMatch { it is NavItemOutcome.Collected }
		assertThat(client.requestedDetailUrls).containsExactly(first.url, second.url)
	}

	@Test
	fun `reports unchanged when the resumed page is not modified`() {
		val cursor = FeedPageCursor(PAGE_ONE_URL, etag = "\"synthetic\"")
		val client = StubNavFeedClient(pages = listOf({ FeedFetchResult.Unchanged }))

		val outcome = NavCollectionWorker(client).collect(cursor)

		assertThat(outcome.completion).isEqualTo(NavCollectionCompletion.Unchanged)
		assertThat(outcome.resumeCursor).isEqualTo(cursor)
		assertThat(outcome.items).isEmpty()
	}

	@Test
	fun `keeps per-item outcomes distinct without stopping the walk`() {
		val fetched = feedItem("synthetic-fetched")
		val notFound = feedItem("synthetic-not-found")
		val failed = feedItem("synthetic-failed")
		val invalid = feedItem("synthetic-invalid")
		val mappingException = NavFeedMappingException(
			MappingErrorReport(listOf(MappingError("$.title", "string", "number"))),
		)
		val client = StubNavFeedClient(
			pages = listOf(
				{ pageResult(PAGE_ONE_URL, null, listOf(fetched, notFound, failed, invalid)) },
			),
			details = mapOf(
				fetched.url to { fetched(fetched.id) },
				notFound.url to { VacancyDetailFetchResult.NotFound },
				failed.url to { throw NavFeedRetryableException("synthetic server failure") },
				invalid.url to { throw mappingException },
			),
		)

		val outcome = NavCollectionWorker(client).collect()

		assertThat(outcome.completion).isEqualTo(NavCollectionCompletion.ReachedTail)
		val byId = outcome.items.associateBy { it.feedItem.id }
		assertThat(byId.getValue(fetched.id)).isInstanceOf(NavItemOutcome.Collected::class.java)
		assertThat(byId.getValue(notFound.id)).isInstanceOf(NavItemOutcome.DetailNotFound::class.java)
		assertThat(byId.getValue(failed.id)).isInstanceOf(NavItemOutcome.Failed::class.java)
		val invalidOutcome = byId.getValue(invalid.id) as NavItemOutcome.Invalid
		assertThat(invalidOutcome.reason).contains("$.title")
	}

	@Test
	fun `classifies an invalid detail url as an invalid item and continues`() {
		val invalid = feedItem("synthetic-invalid-url")
		val valid = feedItem("synthetic-valid-url")
		val client = StubNavFeedClient(
			pages = listOf({ pageResult(PAGE_ONE_URL, null, listOf(invalid, valid)) }),
			details = mapOf(
				invalid.url to { throw NavFeedInvalidDetailUrlException() },
				valid.url to { fetched(valid.id) },
			),
		)

		val outcome = NavCollectionWorker(client).collect()

		assertThat(outcome.completion).isEqualTo(NavCollectionCompletion.ReachedTail)
		assertThat(outcome.items[0]).isInstanceOf(NavItemOutcome.Invalid::class.java)
		assertThat(outcome.items[1]).isInstanceOf(NavItemOutcome.Collected::class.java)
	}

	@Test
	fun `aborts on authorization failure and keeps items collected so far`() {
		val collected = feedItem("synthetic-collected")
		val blocked = feedItem("synthetic-blocked")
		val client = StubNavFeedClient(
			pages = listOf(
				{ pageResult(PAGE_ONE_URL, PAGE_TWO_URL, listOf(collected)) },
				{ pageResult(PAGE_TWO_URL, null, listOf(blocked)) },
			),
			details = mapOf(
				collected.url to { fetched(collected.id) },
				blocked.url to { throw NavFeedUnauthorizedException() },
			),
		)

		val outcome = NavCollectionWorker(client).collect()

		val completion = outcome.completion as NavCollectionCompletion.Aborted
		assertThat(completion.reason).contains("authorization")
		assertThat(outcome.items.map { it.feedItem }).containsExactly(collected)
		// The aborted page is repeated on resume so its unprocessed items are not lost.
		assertThat(outcome.resumeCursor).isEqualTo(FeedPageCursor(PAGE_TWO_URL))
	}

	@Test
	fun `aborts when a feed page cannot be fetched and resumes from that page`() {
		val collected = feedItem("synthetic-collected")
		val client = StubNavFeedClient(
			pages = listOf(
				{ pageResult(PAGE_ONE_URL, PAGE_TWO_URL, listOf(collected)) },
				{ throw NavFeedRetryableException("synthetic server failure") },
			),
			details = mapOf(collected.url to { fetched(collected.id) }),
		)

		val outcome = NavCollectionWorker(client).collect()

		val completion = outcome.completion as NavCollectionCompletion.Aborted
		assertThat(completion.reason).contains("feed page fetch failed")
		assertThat(outcome.items.map { it.feedItem }).containsExactly(collected)
		assertThat(outcome.resumeCursor).isEqualTo(FeedPageCursor(PAGE_TWO_URL))
	}

	@Test
	fun `aborts on rate limiting before the first page`() {
		val client = StubNavFeedClient(
			pages = listOf({ throw NavFeedRateLimitedException(retryAfter = "5") }),
		)

		val outcome = NavCollectionWorker(client).collect()

		val completion = outcome.completion as NavCollectionCompletion.Aborted
		assertThat(completion.reason).contains("rate limited")
		assertThat(outcome.items).isEmpty()
		assertThat(outcome.resumeCursor).isNull()
	}

	private fun fetched(entryId: String): VacancyDetailFetchResult =
		VacancyDetailFetchResult.Fetched(
			VacancyDetailResponse(
				uuid = entryId,
				sistEndret = OffsetDateTime.parse("2026-01-02T03:04:05Z"),
				status = "ACTIVE",
				adContent = null,
			),
		)

	private fun pageResult(
		url: String,
		nextUrl: String?,
		items: List<FeedItem>,
	): FeedFetchResult.Page =
		FeedFetchResult.Page(
			page = FeedPage(
				id = "synthetic-page-id",
				items = items,
				nextUrl = nextUrl,
				nextId = null,
			),
			cursor = FeedPageCursor(url),
			nextCursor = nextUrl?.let { FeedPageCursor(it) },
		)

	private class StubNavFeedClient(
		private val pages: List<() -> FeedFetchResult>,
		private val details: Map<String, () -> VacancyDetailFetchResult> = emptyMap(),
	) : NavFeedClient {
		private var nextPageIndex = 0
		val requestedDetailUrls = mutableListOf<String>()

		override fun fetchPage(cursor: FeedPageCursor?): FeedFetchResult = pages[nextPageIndex++]()

		override fun fetchVacancyDetail(detailUrl: String): VacancyDetailFetchResult {
			requestedDetailUrls.add(detailUrl)
			return details.getValue(detailUrl)()
		}
	}

	private companion object {
		const val PAGE_ONE_URL = "https://pam-stilling-feed.test/api/v1/feed"
		const val PAGE_TWO_URL = "$PAGE_ONE_URL?next=synthetic-next"

		fun feedItem(id: String): FeedItem =
			FeedItem(
				id = id,
				url = "https://pam-stilling-feed.test/api/v1/feedentry/$id",
				title = "Synthetic role",
				contentText = "Synthetic description",
				dateModified = null,
				feedEntry = FeedEntry(
					uuid = id,
					status = "ACTIVE",
					title = "Synthetic feed title",
					businessName = "Synthetic employer",
					municipal = "Synthetic municipality",
					sistEndret = OffsetDateTime.parse("2026-01-02T03:04:05Z"),
				),
			)
	}
}
