package no.jobvacancydata.ingestion.client.nav

// Part of NAV's published API contract so does not change per env
object NavFeedPaths {
	const val FEED_PATH = "/api/v1/feed"

	/** Path for fetching one feed entry's details; append that entry's ID. */
	const val FEED_ENTRY_PATH = "/api/v1/feedentry/"
}
