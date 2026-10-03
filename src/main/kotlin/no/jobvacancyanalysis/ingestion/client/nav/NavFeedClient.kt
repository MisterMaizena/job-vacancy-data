package no.jobvacancyanalysis.ingestion.client.nav

import no.jobvacancyanalysis.ingestion.application.nav.FeedFetchResult
import no.jobvacancyanalysis.ingestion.application.nav.FeedPageCursor

// Fetches one page. Following its next cursor is handled by NavFeedPageWalker
interface NavFeedClient {
	fun fetchPage(cursor: FeedPageCursor? = null): FeedFetchResult
}
