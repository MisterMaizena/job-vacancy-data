package no.jobvacancydata.ingestion.application.brreg

/** Outcome for looking up one organization number in one BRREG register. */
sealed interface BrregLookupResult {
	data class Found(val record: BrregOrganizationRecord) : BrregLookupResult

	data object NotFound : BrregLookupResult
}

data class BrregOrganizationLookup(
	val organizationNumber: String,
	val mainEntity: BrregLookupResult,
	val subunit: BrregLookupResult,
)
