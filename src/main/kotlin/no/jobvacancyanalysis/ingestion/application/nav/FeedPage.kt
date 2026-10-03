package no.jobvacancyanalysis.ingestion.application.nav

import java.time.OffsetDateTime

// One NAV feed response for a page; a null nextUrl marks the current end of the feed
data class FeedPage(
	val id: String,
	val items: List<FeedItem>,
	val nextUrl: String?,
	val nextId: String?,
)

// One feed event: id identifies the event, while feedEntry.uuid identifies the ad
data class FeedItem(
	val id: String,
	val url: String,
	val title: String,
	val contentText: String,
	val dateModified: OffsetDateTime?,
	val feedEntry: FeedEntry,
)

data class FeedEntry(
	val uuid: String,
	val status: String,
	val title: String,
	val businessName: String,
	val municipal: String,
	val sistEndret: OffsetDateTime,
)
