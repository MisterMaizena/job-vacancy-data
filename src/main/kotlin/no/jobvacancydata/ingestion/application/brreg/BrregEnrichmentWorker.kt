package no.jobvacancydata.ingestion.application.brreg

import no.jobvacancydata.ingestion.client.brreg.BrregRegistryClient

/**
 * BRREG enrichment flow: resolves each organization number against the
 * main-entity and subunit registers and derives one explicit status per organization.
 * Lookup uses exact organization-number identity only.
 */
class BrregEnrichmentWorker(
	private val client: BrregRegistryClient,
) {
	fun enrich(organizationNumbers: Collection<String>): BrregEnrichmentOutcome {
		val lookups = client.lookup(organizationNumbers.distinct())
		return BrregEnrichmentOutcome(
			organizations = lookups.map(::toEnrichment),
		)
	}

	private fun toEnrichment(lookup: BrregOrganizationLookup): BrregOrganizationEnrichment {
		val status = when {
			lookup.mainEntity is BrregLookupResult.Failed ||
				lookup.subunit is BrregLookupResult.Failed -> BrregEnrichmentStatus.FAILED

			lookup.mainEntity is BrregLookupResult.Invalid ||
				lookup.subunit is BrregLookupResult.Invalid -> BrregEnrichmentStatus.INVALID

			lookup.mainEntity is BrregLookupResult.NotFound &&
				lookup.subunit is BrregLookupResult.NotFound -> BrregEnrichmentStatus.NOT_FOUND

			else -> BrregEnrichmentStatus.ENRICHED
		}
		return BrregOrganizationEnrichment(
			organizationNumber = lookup.organizationNumber,
			status = status,
			mainEntity = lookup.mainEntity,
			subunit = lookup.subunit,
		)
	}
}
