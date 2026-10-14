package no.jobvacancydata.ingestion.application.nav

sealed interface VacancyDetailFetchResult {
	data class Fetched(val detail: VacancyDetailResponse) : VacancyDetailFetchResult

	data object NotFound : VacancyDetailFetchResult
}
