package no.jobvacancydata.ingestion.client.nav

// Part of NAV's published API contract so does not change per env
object NavFeedPaths {
	const val FEED_PATH = "/api/v1/feed"

	/** Feed-entry detail path prefix; a detail URL appends the entry ID as its last segment. */
	const val FEED_ENTRY_PATH = "/api/v1/feedentry/"
}
