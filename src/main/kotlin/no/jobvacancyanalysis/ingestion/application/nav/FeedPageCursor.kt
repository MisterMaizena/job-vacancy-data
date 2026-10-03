package no.jobvacancyanalysis.ingestion.application.nav

// URL chooses the page to fetch; ETag and Last-Modified lets NAV say if that same page has changed.
data class FeedPageCursor(
	val url: String,
	val etag: String? = null,
	val lastModified: String? = null,
)
