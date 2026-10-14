package no.jobvacancydata.ingestion.application.brreg

/** Result of one non-persistent BRREG enrichment run. */
data class BrregEnrichmentOutcome(
	val organizations: List<BrregOrganizationEnrichment>,
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
	 * Both registers confirmed no record. This is unexpected, as a resource should be found considering
	 * all Orgs need an Org.Nr, and we have that from NAV.
	 *
	 */
	NOT_FOUND,

	/** A register lookup did not complete; enrichment remains pending. */
	FAILED,

	// TODO: Iron out how to deal with this properly
	/** A returned record failed validation and needs review. */
	INVALID,
}
