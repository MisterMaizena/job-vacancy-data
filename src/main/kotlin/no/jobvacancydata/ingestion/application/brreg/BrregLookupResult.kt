package no.jobvacancydata.ingestion.application.brreg

/**
 * Outcome for looking up one organization number in one BRREG register.
 */
sealed interface BrregLookupResult {
	/** The register returned a record: current, deleted, or gone. */
	data class Found(val record: BrregOrganizationRecord) : BrregLookupResult

	data object NotFound : BrregLookupResult

	/**
	 * The lookup didn't complete: transport, server, rate-limit, or unexpected
	 * response failure after bounded retries. The organization number remains pending.
	 */
	data class Failed(val reason: String) : BrregLookupResult

	/**
	 * The returned record failed validation or was ambiguous
	 * (for example returned twice). Needs handling / review.
	 */
	data class Invalid(val reason: String) : BrregLookupResult
}

data class BrregOrganizationLookup(
	val organizationNumber: String,
	val mainEntity: BrregLookupResult,
	val subunit: BrregLookupResult,
)
