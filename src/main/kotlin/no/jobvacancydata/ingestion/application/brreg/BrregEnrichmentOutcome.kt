package no.jobvacancydata.ingestion.application.brreg

/** Result of one non-persistent BRREG enrichment run. */
data class BrregEnrichmentOutcome(
	val organizations: List<BrregOrganizationEnrichment>,
	/** IDs that are not nine digits and were never sent to BRREG; visible instead of dropped. */
	val rejectedOrganizationNumbers: List<String>,
)

data class BrregOrganizationEnrichment(
	val organizationNumber: String,
	val status: BrregEnrichmentStatus,
	val mainEntity: BrregLookupResult,
	val subunit: BrregLookupResult,
)

enum class BrregEnrichmentStatus {
	/** At least one register returned a record and neither register failed or was invalid. */
	ENRICHED,

	/**
	 * Both registers confirmed no record. This is unexpected for a NAV-referenced
	 * organization number, so it is an explicit outcome, not a successful match.
	 */
	NOT_FOUND,

	/** A register lookup did not complete; enrichment remains pending. */
	FAILED,

	/** A returned record failed validation and needs review. */
	INVALID,
}
