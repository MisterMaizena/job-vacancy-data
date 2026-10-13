package no.jobvacancydata.ingestion.client.nav

import no.jobvacancydata.ingestion.application.nav.FeedFetchResult
import no.jobvacancydata.ingestion.application.nav.FeedPageCursor
import no.jobvacancydata.ingestion.application.nav.VacancyDetailFetchResult

interface NavFeedClient {
	/** Fetches one page; use NavFeedPageWalker to follow continuation cursors. */
	fun fetchPage(cursor: FeedPageCursor? = null): FeedFetchResult

	/**
	 * Fetches the vacancy detail a feed item's url points to.
	 * NAV's documented 404 returns [VacancyDetailFetchResult.NotFound]; any other
	 * failure throws a [NavFeedException] subtype.
	 */
	fun fetchVacancyDetail(detailUrl: String): VacancyDetailFetchResult
}
