package no.jobvacancyanalysis.ingestion.application.nav

/** Identifies a feed page and holds its validators for conditional requests. */
data class FeedPageCursor(
	val url: String,
	val etag: String? = null,
	val lastModified: String? = null,
)
