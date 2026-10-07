package no.jobvacancyanalysis.ingestion.client.nav

import no.jobvacancyanalysis.ingestion.client.validation.MappingErrorReport

/**
 * Thrown by the page and vacancy-detail mappers when response JSON cannot be mapped.
 * The report lists invalid field paths and expected/actual types.
 */
class NavFeedMappingException(
	val report: MappingErrorReport,
	cause: Throwable? = null,
) : NavFeedMalformedResponseException(report.message("NAV"), cause)
