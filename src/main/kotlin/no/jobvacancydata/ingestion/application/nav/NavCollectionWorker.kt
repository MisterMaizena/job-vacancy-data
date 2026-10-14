package no.jobvacancydata.ingestion.application.nav

import no.jobvacancydata.ingestion.client.nav.NavFeedClient
import no.jobvacancydata.ingestion.client.nav.NavFeedException
import no.jobvacancydata.ingestion.client.nav.NavFeedInvalidDetailUrlException
import no.jobvacancydata.ingestion.client.nav.NavFeedMalformedResponseException
import no.jobvacancydata.ingestion.client.nav.NavFeedPageWalkOutcome
import no.jobvacancydata.ingestion.client.nav.NavFeedPageWalker
import no.jobvacancydata.ingestion.client.nav.NavFeedRateLimitedException
import no.jobvacancydata.ingestion.client.nav.NavFeedRetryableException
import no.jobvacancydata.ingestion.client.nav.NavFeedUnauthorizedException

/**
 * NAV collection flow: walks feed pages from an optional cursor and
 * retrieves each item's vacancy detail. Per-item failures become explicit outcomes;
 * source-wide failures (authorization, rate limiting, unrecoverable page fetch) abort
 * the run and keep what was collected. Persistence and durable checkpoints will be integrated
 * at a later stage.
 */
class NavCollectionWorker(
	private val client: NavFeedClient,
) {
	fun collect(initialCursor: FeedPageCursor? = null): NavCollectionOutcome {
		val items = mutableListOf<NavItemOutcome>()
		// The page being processed becomes the resume point until the page is done;
		// afterwards the next page takes over.
		var resumeCursor = initialCursor
		return try {
			val walk = NavFeedPageWalker(client).walk(initialCursor) { pageResult ->
				resumeCursor = pageResult.cursor
				pageResult.page.items.forEach { item -> items += collectItem(item) }
				resumeCursor = pageResult.nextCursor
			}
			NavCollectionOutcome(
				completion = when (walk.outcome) {
					NavFeedPageWalkOutcome.REACHED_TAIL -> NavCollectionCompletion.ReachedTail
					NavFeedPageWalkOutcome.UNCHANGED -> NavCollectionCompletion.Unchanged
				},
				resumeCursor = walk.lastCursor,
				items = items,
			)
		} catch (exception: NavFeedException) {
			NavCollectionOutcome(
				completion = NavCollectionCompletion.Aborted(abortReason(exception)),
				resumeCursor = resumeCursor,
				items = items,
			)
		}
	}

	private fun collectItem(item: FeedItem): NavItemOutcome =
		try {
			when (val result = client.fetchVacancyDetail(item.url)) {
				is VacancyDetailFetchResult.Fetched ->
					NavItemOutcome.Collected(item, result.detail)

				VacancyDetailFetchResult.NotFound ->
					NavItemOutcome.DetailNotFound(item)
			}
		} catch (exception: NavFeedUnauthorizedException) {
			// Source-wide failure: retrying every remaining item would fail alike.
			throw exception
		} catch (exception: NavFeedRateLimitedException) {
			throw exception
		} catch (exception: NavFeedInvalidDetailUrlException) {
			NavItemOutcome.Invalid(item, exception.message ?: "NAV detail URL is invalid")
		} catch (exception: NavFeedRetryableException) {
			NavItemOutcome.Failed(item, "NAV detail lookup failed after bounded retries")
		} catch (exception: NavFeedMalformedResponseException) {
			NavItemOutcome.Invalid(item, exception.message ?: "NAV detail response failed validation")
		} catch (exception: NavFeedException) {
			NavItemOutcome.Failed(item, "NAV detail lookup failed (${exception.javaClass.simpleName})")
		}

	private fun abortReason(exception: NavFeedException): String = when (exception) {
		is NavFeedUnauthorizedException -> "NAV authorization failed"
		is NavFeedRateLimitedException -> "NAV rate limited the request"
		is NavFeedRetryableException -> "NAV feed page fetch failed after bounded retries"
		else -> "NAV feed walk failed (${exception.javaClass.simpleName})"
	}
}
