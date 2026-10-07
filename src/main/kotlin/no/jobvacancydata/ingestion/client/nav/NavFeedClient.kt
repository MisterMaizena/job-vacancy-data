package no.jobvacancydata.ingestion.client.nav

import no.jobvacancydata.ingestion.application.nav.FeedFetchResult
import no.jobvacancydata.ingestion.application.nav.FeedPageCursor

interface NavFeedClient {
	/** Fetches one page; use NavFeedPageWalker to follow continuation cursors. */
	fun fetchPage(cursor: FeedPageCursor? = null): FeedFetchResult
}
