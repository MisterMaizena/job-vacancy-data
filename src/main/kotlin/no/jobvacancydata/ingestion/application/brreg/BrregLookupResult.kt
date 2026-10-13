package no.jobvacancydata.ingestion.application.brreg

/**
 * Outcome for looking up one organization number in one BRREG register.
 * A failure is never reported as [NotFound], and one failed record never
 * discards the valid results of its peers.
 */
sealed interface BrregLookupResult {
	/** The register returned a record: current, deleted, or gone. */
	data class Found(val record: BrregOrganizationRecord) : BrregLookupResult

	/** The authoritative detail endpoint confirmed no record (HTTP 404). */
	data object NotFound : BrregLookupResult

	/**
	 * The lookup did not complete: transport, server, rate-limit, or unexpected
	 * response failure after bounded retries. The organization number remains pending.
	 */
	data class Failed(val reason: String) : BrregLookupResult

	/**
	 * The returned record failed source-contract validation or was ambiguous
	 * (for example returned twice); it needs review rather than a guess.
	 */
	data class Invalid(val reason: String) : BrregLookupResult
}

data class BrregOrganizationLookup(
	val organizationNumber: String,
	val mainEntity: BrregLookupResult,
	val subunit: BrregLookupResult,
)
