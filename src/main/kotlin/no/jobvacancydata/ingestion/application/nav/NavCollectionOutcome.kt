package no.jobvacancydata.ingestion.application.nav

/**
 * Result of one non-persistent NAV collection run. Intended for a future
 * persistence layer to consume collected items and resume from the cursor.
 */
data class NavCollectionOutcome(
	val completion: NavCollectionCompletion,
	/**
	 * Where a later run should resume: the walk's last page cursor on completion,
	 * or the page to refetch when the run aborted.
	 */
	val resumeCursor: FeedPageCursor?,
	val items: List<NavItemOutcome>,
)

sealed interface NavCollectionCompletion {
	/** Walked the feed to its current end. */
	data object ReachedTail : NavCollectionCompletion

	/** The resumed page had not changed (conditional request). */
	data object Unchanged : NavCollectionCompletion

	/**
	 * Collection stopped early on a source-wide failure (authorization, rate
	 * limiting, or an unrecoverable page fetch). Items collected so far are kept;
	 * the resume cursor may repeat the partially processed page on replay.
	 */
	data class Aborted(val reason: String) : NavCollectionCompletion
}

/** Per-feed-item outcome of vacancy-detail collection. */
sealed interface NavItemOutcome {
	val feedItem: FeedItem

	/** Detail retrieved and mapped. */
	data class Collected(
		override val feedItem: FeedItem,
		val detail: VacancyDetailResponse,
	) : NavItemOutcome

	/** NAV returned 404 for the feed item's detail URL. */
	data class DetailNotFound(override val feedItem: FeedItem) : NavItemOutcome

	/** Detail lookup failed after bounded retries; the item remains pending. */
	data class Failed(
		override val feedItem: FeedItem,
		val reason: String,
	) : NavItemOutcome

	// TODO: Iron out how to deal with this properly
	/** The detail response failed validation or identity verification; needs handling or review. */
	data class Invalid(
		override val feedItem: FeedItem,
		val reason: String,
	) : NavItemOutcome
}
