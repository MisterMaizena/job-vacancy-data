package no.jobvacancyanalysis.ingestion.client.nav

import no.jobvacancyanalysis.ingestion.application.nav.FeedFetchResult
import no.jobvacancyanalysis.ingestion.application.nav.FeedPageCursor

interface NavFeedClient {
	/** Fetches one page; use NavFeedPageWalker to follow continuation cursors. */
	fun fetchPage(cursor: FeedPageCursor? = null): FeedFetchResult
}
