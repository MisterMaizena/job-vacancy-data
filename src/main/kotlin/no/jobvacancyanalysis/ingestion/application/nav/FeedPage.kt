package no.jobvacancyanalysis.ingestion.application.nav

import java.time.OffsetDateTime

/** One NAV feed page; a null nextUrl means the feed has reached its current end. */
data class FeedPage(
	val id: String,
	val items: List<FeedItem>,
	val nextUrl: String?,
	val nextId: String?,
)

/** One feed event; id identifies the event, while feedEntry.uuid identifies the advertisement. */
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
