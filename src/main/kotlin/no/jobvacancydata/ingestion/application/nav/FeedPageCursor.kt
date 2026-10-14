package no.jobvacancydata.ingestion.application.nav

/** Identifies a feed page and stores its ETag and Last-Modified values to check for changes. */
data class FeedPageCursor(
	val url: String,
	val etag: String? = null,
	val lastModified: String? = null,
)
