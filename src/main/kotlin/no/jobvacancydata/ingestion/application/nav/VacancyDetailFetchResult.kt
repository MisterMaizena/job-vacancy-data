package no.jobvacancydata.ingestion.application.nav

/**
 * Outcome of fetching one vacancy detail. NotFound mirrors NAV's documented 404
 * response for the feed-entry endpoint; all other failures throw typed exceptions.
 */
sealed interface VacancyDetailFetchResult {
	data class Fetched(val detail: VacancyDetailResponse) : VacancyDetailFetchResult

	data object NotFound : VacancyDetailFetchResult
}
