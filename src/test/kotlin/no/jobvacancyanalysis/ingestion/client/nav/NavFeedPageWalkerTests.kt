package no.jobvacancyanalysis.ingestion.client.nav

import no.jobvacancyanalysis.ingestion.application.nav.FeedFetchResult
import no.jobvacancyanalysis.ingestion.application.nav.FeedPage
import no.jobvacancyanalysis.ingestion.application.nav.FeedPageCursor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NavFeedPageWalkerTests {
	@Test
	fun `walks pages to tail and returns last cursor`() {
		val client = StubNavFeedClient(
			listOf(
				page(FIRST_PAGE_URL, NEXT_PAGE_URL),
				page(NEXT_PAGE_URL, null),
			),
		)
		val receivedPages = mutableListOf<FeedFetchResult.Page>()

		val result = NavFeedPageWalker(client).walk { receivedPages.add(it) }

		assertThat(result).isEqualTo(
			NavFeedPageWalkResult(
				outcome = NavFeedPageWalkOutcome.REACHED_TAIL,
				lastCursor = FeedPageCursor(NEXT_PAGE_URL),
			),
		)
		assertThat(receivedPages).hasSize(2)
		assertThat(client.requestedCursors).containsExactly(
			null,
			FeedPageCursor(NEXT_PAGE_URL),
		)
	}

	@Test
	fun `returns unchanged outcome and cursor`() {
		val cursor = FeedPageCursor(FIRST_PAGE_URL, etag = "\"synthetic\"")
		val client = StubNavFeedClient(listOf(FeedFetchResult.Unchanged))

		val result = NavFeedPageWalker(client).walk(cursor) {}

		assertThat(result).isEqualTo(
			NavFeedPageWalkResult(
				outcome = NavFeedPageWalkOutcome.UNCHANGED,
				lastCursor = cursor,
			),
		)
		assertThat(client.requestedCursors).containsExactly(cursor)
	}

	@Test
	fun `rejects repeated continuation before fetching the repeated page`() {
		val client = StubNavFeedClient(listOf(page(FIRST_PAGE_URL, FIRST_PAGE_URL)))

		val exception = assertThrows<NavFeedException> {
			NavFeedPageWalker(client).walk {}
		}

		assertThat(exception.message).isEqualTo("NAV feed continuation cycle detected")
		assertThat(client.requestedCursors).containsExactly(null)
	}

	private fun page(
		cursorUrl: String,
		nextUrl: String?,
	): FeedFetchResult.Page =
		FeedFetchResult.Page(
			page = FeedPage(
				id = "synthetic-page-id",
				items = emptyList(),
				nextUrl = null,
				nextId = null,
			),
			cursor = FeedPageCursor(cursorUrl),
			nextCursor = nextUrl?.let { FeedPageCursor(it) },
		)

	private class StubNavFeedClient(
		private val results: List<FeedFetchResult>,
	) : NavFeedClient {
		val requestedCursors = mutableListOf<FeedPageCursor?>()
		private var nextResultIndex = 0

		override fun fetchPage(cursor: FeedPageCursor?): FeedFetchResult {
			requestedCursors.add(cursor)
			return results[nextResultIndex++]
		}
	}

	private companion object {
		const val FIRST_PAGE_URL = "https://pam-stilling-feed.test/api/v1/feed"
		const val NEXT_PAGE_URL = "$FIRST_PAGE_URL?next=synthetic-next"
	}
}
